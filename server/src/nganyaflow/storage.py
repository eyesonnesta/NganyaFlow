import json
import sqlite3
from contextlib import contextmanager
from datetime import UTC, datetime, timedelta
from pathlib import Path

DAILY_REPORT_LIMIT = 300


class Conflict(ValueError):
    pass


class Store:
    def __init__(self, path: Path):
        self.path = path
        path.parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS sessions (
                    id TEXT PRIMARY KEY, payload TEXT NOT NULL, ended_at TEXT
                );
                CREATE TABLE IF NOT EXISTS observations (
                    id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(id),
                    observed_at TEXT NOT NULL, event_type TEXT NOT NULL, payload TEXT NOT NULL,
                    UNIQUE(session_id, observed_at, event_type)
                );
                CREATE INDEX IF NOT EXISTS observation_session ON observations(session_id);
                CREATE TABLE IF NOT EXISTS voided_observations (
                    id TEXT PRIMARY KEY REFERENCES observations(id) ON DELETE CASCADE,
                    voided_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS access_codes (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, code_hash TEXT NOT NULL UNIQUE,
                    role TEXT NOT NULL, label TEXT NOT NULL, created_at TEXT NOT NULL,
                    expires_at TEXT NOT NULL, revoked INTEGER NOT NULL DEFAULT 0
                );
                CREATE TABLE IF NOT EXISTS tokens (
                    token_hash TEXT PRIMARY KEY, code_id INTEGER NOT NULL REFERENCES access_codes(id),
                    role TEXT NOT NULL, device_name TEXT, expires_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS reports (
                    id TEXT PRIMARY KEY, install_id TEXT NOT NULL, stage_id TEXT NOT NULL,
                    route_id TEXT NOT NULL, direction TEXT NOT NULL, boarded_at TEXT NOT NULL,
                    consent_version TEXT NOT NULL, received_at TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS report_install ON reports(install_id);
                CREATE INDEX IF NOT EXISTS report_scope ON reports(route_id, direction, boarded_at);
                CREATE TABLE IF NOT EXISTS deletion_requests (
                    id TEXT PRIMARY KEY, install_id TEXT NOT NULL, requested_at TEXT NOT NULL,
                    completed_at TEXT, reports_deleted INTEGER NOT NULL DEFAULT 0
                );
                CREATE INDEX IF NOT EXISTS deletion_install ON deletion_requests(install_id);
                CREATE TABLE IF NOT EXISTS retracted_reports (
                    id TEXT PRIMARY KEY, install_id TEXT NOT NULL, retracted_at TEXT NOT NULL
                );
            """)

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        try:
            with db:
                db.execute("BEGIN IMMEDIATE")
                yield db
        finally:
            db.close()

    def add_session(self, session):
        payload = session.model_dump_json()
        with self.connect() as db:
            old = db.execute(
                "SELECT payload FROM sessions WHERE id=?", (str(session.id),)
            ).fetchone()
            if old:
                if old["payload"] != payload:
                    raise Conflict("Session ID reused with different content")
                return
            db.execute("INSERT INTO sessions(id,payload) VALUES (?,?)", (str(session.id), payload))

    def close_session(self, session_id, ended_at):
        with self.connect() as db:
            row = db.execute("SELECT * FROM sessions WHERE id=?", (session_id,)).fetchone()
            if not row:
                raise KeyError(session_id)
            from datetime import datetime

            start = datetime.fromisoformat(json.loads(row["payload"])["started_at"])
            if ended_at < start:
                raise ValueError("Session cannot end before it starts")
            if row["ended_at"] and datetime.fromisoformat(row["ended_at"]) != ended_at:
                raise Conflict("Session already closed at a different time")
            times = db.execute(
                "SELECT observed_at FROM observations WHERE session_id=?", (session_id,)
            ).fetchall()
            if any(datetime.fromisoformat(r[0]) > ended_at for r in times):
                raise ValueError("Session end precedes a stored observation")
            db.execute(
                "UPDATE sessions SET ended_at=? WHERE id=?", (ended_at.isoformat(), session_id)
            )

    def add_batch(self, records, *, simulator=False):
        from datetime import datetime

        accepted, duplicates = [], []
        with self.connect() as db:
            for record in records:
                ident, payload = str(record.id), record.model_dump_json(exclude_none=True)
                old = db.execute("SELECT payload FROM observations WHERE id=?", (ident,)).fetchone()
                if old:
                    if old[0] != payload:
                        raise Conflict("Observation ID reused with different content")
                    duplicates.append(ident)
                    continue
                session = db.execute(
                    "SELECT * FROM sessions WHERE id=?", (str(record.session_id),)
                ).fetchone()
                if not session:
                    raise ValueError("Upload the session before its observations")
                scope = json.loads(session["payload"])
                if simulator and db.execute(
                    """SELECT 1 FROM sessions WHERE ended_at IS NULL
                       AND json_extract(payload,'$.dataset_id') != ?
                       AND json_extract(payload,'$.stage_id')=?
                       AND json_extract(payload,'$.route_id')=?
                       AND json_extract(payload,'$.direction')=? LIMIT 1""",
                    (
                        scope["dataset_id"], scope["stage_id"], scope["route_id"],
                        scope["direction"],
                    ),
                ).fetchone():
                    continue
                start = datetime.fromisoformat(scope["started_at"])
                end = datetime.fromisoformat(session["ended_at"]) if session["ended_at"] else None
                if record.observed_at < start or (end and record.observed_at > end):
                    raise ValueError("Observation lies outside its session")
                try:
                    db.execute(
                        "INSERT INTO observations VALUES (?,?,?,?,?)",
                        (
                            ident,
                            str(record.session_id),
                            record.observed_at.isoformat(),
                            record.event_type,
                            payload,
                        ),
                    )
                except sqlite3.IntegrityError as exc:
                    raise Conflict("Event already recorded at this session and timestamp") from exc
                accepted.append(ident)
        return {"accepted_ids": accepted, "duplicate_ids": duplicates}

    def arrivals(self, *, closed_only=False):
        with self.connect() as db:
            rows = db.execute("""
                SELECT o.payload AS event, s.payload AS session, s.ended_at FROM observations o
                JOIN sessions s ON o.session_id=s.id WHERE o.event_type='arrival'
                  AND NOT EXISTS (SELECT 1 FROM voided_observations v WHERE v.id=o.id)
            """).fetchall()
        return [
            dict(json.loads(r["session"]), **json.loads(r["event"]))
            for r in rows
            if not closed_only or r["ended_at"]
        ]

    def import_bundle(self, sessions, ends, events):
        """All records commit together, including completion boundaries."""
        accepted = duplicates = 0
        with self.connect() as db:
            for sid, session in sessions.items():
                payload, end = session.model_dump_json(), ends[sid].isoformat()
                old = db.execute("SELECT * FROM sessions WHERE id=?", (sid,)).fetchone()
                if old:
                    if old["payload"] != payload or old["ended_at"] != end:
                        raise Conflict("Existing session conflicts with the imported dataset")
                else:
                    db.execute("INSERT INTO sessions VALUES (?,?,?)", (sid, payload, end))
            for event in events:
                ident, payload = str(event.id), event.model_dump_json(exclude_none=True)
                old = db.execute("SELECT payload FROM observations WHERE id=?", (ident,)).fetchone()
                if old:
                    if old[0] != payload:
                        raise Conflict("Existing observation conflicts with the imported dataset")
                    duplicates += 1
                    continue
                try:
                    db.execute(
                        "INSERT INTO observations VALUES (?,?,?,?,?)",
                        (
                            ident,
                            str(event.session_id),
                            event.observed_at.isoformat(),
                            event.event_type,
                            payload,
                        ),
                    )
                except sqlite3.IntegrityError as exc:
                    raise Conflict("An imported event conflicts with a stored timestamp") from exc
                accepted += 1
        return {
            "accepted_observations": accepted,
            "duplicate_observations": duplicates,
            "sessions": len(sessions),
        }

    # ---- observations by scope -------------------------------------------------

    def arrivals_for(self, group):
        """Arrival events of one stage/route/direction, across all sessions."""
        stage_id, route_id, direction = group
        with self.connect() as db:
            rows = db.execute(
                """
                SELECT o.payload AS event, s.payload AS session FROM observations o
                JOIN sessions s ON o.session_id=s.id
                WHERE o.event_type='arrival'
                  AND NOT EXISTS (SELECT 1 FROM voided_observations v WHERE v.id=o.id)
                  AND json_extract(s.payload,'$.stage_id')=?
                  AND json_extract(s.payload,'$.route_id')=?
                  AND json_extract(s.payload,'$.direction')=?
                """,
                (stage_id, route_id, direction),
            ).fetchall()
        return [dict(json.loads(r["session"]), **json.loads(r["event"])) for r in rows]

    def arrivals_for_route(self, route_id, direction):
        with self.connect() as db:
            rows = db.execute(
                """
                SELECT o.payload AS event, s.payload AS session FROM observations o
                JOIN sessions s ON o.session_id=s.id
                WHERE o.event_type='arrival'
                  AND NOT EXISTS (SELECT 1 FROM voided_observations v WHERE v.id=o.id)
                  AND json_extract(s.payload,'$.route_id')=?
                  AND json_extract(s.payload,'$.direction')=?
                """,
                (route_id, direction),
            ).fetchall()
        return [dict(json.loads(r["session"]), **json.loads(r["event"])) for r in rows]

    def void_observation(self, observation_id):
        """Keep the original record while excluding it from every arrival query."""
        with self.connect() as db:
            if not db.execute(
                "SELECT 1 FROM observations WHERE id=?", (observation_id,)
            ).fetchone():
                raise KeyError(observation_id)
            db.execute(
                "INSERT OR IGNORE INTO voided_observations VALUES (?,?)",
                (observation_id, _now()),
            )

    def non_simulator_groups(self, dataset_id):
        with self.connect() as db:
            rows = db.execute(
                """SELECT payload FROM sessions WHERE ended_at IS NULL
                   AND json_extract(payload,'$.dataset_id') != ?""", (dataset_id,)
            ).fetchall()
        return {
            (s["stage_id"], s["route_id"], s["direction"])
            for s in (json.loads(r["payload"]) for r in rows)
        }

    def clean_simulator(self, dataset_id, now):
        """Recover only live-simulator sessions and trim only their old observations."""
        with self.connect() as db:
            leftovers = db.execute(
                """SELECT s.id, s.payload, MAX(o.observed_at) AS latest FROM sessions s
                   LEFT JOIN observations o ON o.session_id=s.id
                   WHERE s.ended_at IS NULL AND json_extract(s.payload,'$.dataset_id')=?
                   GROUP BY s.id""", (dataset_id,)
            ).fetchall()
            for session in leftovers:
                start = datetime.fromisoformat(json.loads(session["payload"])["started_at"])
                latest = datetime.fromisoformat(session["latest"]) if session["latest"] else start
                end = max(now, start, latest)
                db.execute(
                    "UPDATE sessions SET ended_at=? WHERE id=?",
                    (end.isoformat(), session["id"]),
                )
            removed = db.execute(
                """DELETE FROM observations WHERE observed_at<? AND session_id IN
                   (SELECT id FROM sessions WHERE json_extract(payload,'$.dataset_id')=?)""",
                ((now - timedelta(hours=24)).isoformat(), dataset_id),
            ).rowcount
        return {"closed_sessions": len(leftovers), "removed_observations": removed}

    # ---- access codes and tokens -----------------------------------------------

    def add_code(self, code_hash, role, label, expires_at):
        with self.connect() as db:
            try:
                cursor = db.execute(
                    "INSERT INTO access_codes(code_hash,role,label,created_at,expires_at)"
                    " VALUES (?,?,?,?,?)",
                    (code_hash, role, label, _now(), expires_at.astimezone(UTC).isoformat()),
                )
            except sqlite3.IntegrityError as exc:
                raise Conflict("That access code already exists") from exc
            return cursor.lastrowid

    def codes(self):
        with self.connect() as db:
            return [
                dict(r)
                for r in db.execute(
                    "SELECT id,role,label,created_at,expires_at,revoked FROM access_codes"
                    " ORDER BY id DESC"
                ).fetchall()
            ]

    def has_codes(self):
        with self.connect() as db:
            return db.execute("SELECT 1 FROM access_codes LIMIT 1").fetchone() is not None

    def revoke_code(self, code_id):
        with self.connect() as db:
            changed = db.execute(
                "UPDATE access_codes SET revoked=1 WHERE id=?", (code_id,)
            ).rowcount
            db.execute("DELETE FROM tokens WHERE code_id=?", (code_id,))
        if not changed:
            raise KeyError(code_id)

    def redeem(self, code_hash, token_hash, token_ttl, device_name):
        """Exchange a valid code for a token. Returns (role, expires_at) or None."""
        now = datetime.now(UTC)
        with self.connect() as db:
            row = db.execute(
                "SELECT id,role,expires_at,revoked FROM access_codes WHERE code_hash=?",
                (code_hash,),
            ).fetchone()
            if not row or row["revoked"] or datetime.fromisoformat(row["expires_at"]) <= now:
                return None
            # A token never outlives its code.
            expires = min(now + token_ttl, datetime.fromisoformat(row["expires_at"]))
            db.execute("DELETE FROM tokens WHERE expires_at<=?", (now.isoformat(),))
            db.execute(
                "INSERT INTO tokens VALUES (?,?,?,?,?)",
                (token_hash, row["id"], row["role"], device_name, expires.isoformat()),
            )
        return row["role"], expires

    def token_role(self, token_hash):
        now = datetime.now(UTC)
        with self.connect() as db:
            row = db.execute(
                "SELECT t.role, t.expires_at, c.revoked, c.expires_at AS code_expires"
                " FROM tokens t JOIN access_codes c ON c.id=t.code_id WHERE t.token_hash=?",
                (token_hash,),
            ).fetchone()
        if not row or row["revoked"]:
            return None
        if datetime.fromisoformat(row["expires_at"]) <= now:
            return None
        if datetime.fromisoformat(row["code_expires"]) <= now:
            return None
        return row["role"]

    # ---- rider boarding reports --------------------------------------------------

    def add_reports(self, records):
        accepted, duplicates, rejected = [], [], []
        now = datetime.now(UTC)
        with self.connect() as db:
            for record in records:
                ident, install = str(record.id), str(record.install_id)
                old = db.execute("SELECT * FROM reports WHERE id=?", (ident,)).fetchone()
                if old:
                    same = (
                        old["install_id"],
                        old["stage_id"],
                        old["route_id"],
                        old["direction"],
                        old["boarded_at"],
                    ) == (
                        install,
                        record.stage_id,
                        record.route_id,
                        record.direction,
                        record.boarded_at.isoformat(),
                    )
                    if not same:
                        raise Conflict("Report ID reused with different content")
                    duplicates.append(ident)
                    continue
                if db.execute("SELECT 1 FROM retracted_reports WHERE id=?", (ident,)).fetchone():
                    # The rider undid this report; a delayed retry must not bring it back.
                    rejected.append({"id": ident, "reason": "retracted"})
                    continue
                retired = db.execute(
                    "SELECT 1 FROM deletion_requests WHERE install_id=?", (install,)
                ).fetchone()
                if retired:
                    # Deletion retires the installation ID for good, so nothing depends on
                    # comparing phone and server clocks. The app continues with a new ID.
                    rejected.append({"id": ident, "reason": "deleted_by_request"})
                    continue
                recent = db.execute(
                    "SELECT COUNT(*) FROM reports WHERE install_id=? AND received_at>?",
                    (install, (now - timedelta(days=1)).isoformat()),
                ).fetchone()[0]
                if recent >= DAILY_REPORT_LIMIT:
                    rejected.append({"id": ident, "reason": "daily_limit"})
                    continue
                db.execute(
                    "INSERT INTO reports VALUES (?,?,?,?,?,?,?,?)",
                    (
                        ident,
                        install,
                        record.stage_id,
                        record.route_id,
                        record.direction,
                        record.boarded_at.isoformat(),
                        record.consent_version,
                        now.isoformat(),
                    ),
                )
                accepted.append(ident)
        return {"accepted_ids": accepted, "duplicate_ids": duplicates, "rejected": rejected}

    def retract_report(self, report_id, install_id):
        """Remove one report and keep a marker so a retried upload cannot restore it."""
        with self.connect() as db:
            removed = db.execute(
                "DELETE FROM reports WHERE id=? AND install_id=?", (report_id, install_id)
            ).rowcount
            if removed:
                db.execute(
                    "INSERT OR IGNORE INTO retracted_reports VALUES (?,?,?)",
                    (report_id, install_id, _now()),
                )
                return True
            # Repeating an undo (for example after a timeout) succeeds without change.
            return bool(
                db.execute(
                    "SELECT 1 FROM retracted_reports WHERE id=? AND install_id=?",
                    (report_id, install_id),
                ).fetchone()
            )

    def delete_install(self, request_id, install_id):
        """Erase every report of one install and retire that install ID.

        Replaying the same request is safe. Later uploads carrying the retired ID are
        refused whatever their timestamp.
        """
        with self.connect() as db:
            old = db.execute("SELECT * FROM deletion_requests WHERE id=?", (request_id,)).fetchone()
            if old:
                if old["install_id"] != install_id:
                    raise Conflict("Deletion request ID reused for another installation")
                return dict(old)
            removed = db.execute("DELETE FROM reports WHERE install_id=?", (install_id,)).rowcount
            now = _now()
            db.execute(
                "INSERT INTO deletion_requests VALUES (?,?,?,?,?)",
                (request_id, install_id, now, now, removed),
            )
            return {
                "id": request_id,
                "install_id": install_id,
                "requested_at": now,
                "completed_at": now,
                "reports_deleted": removed,
            }

    def deletion_status(self, request_id):
        with self.connect() as db:
            row = db.execute("SELECT * FROM deletion_requests WHERE id=?", (request_id,)).fetchone()
        return dict(row) if row else None

    def reports_for_route(self, route_id, direction, since):
        with self.connect() as db:
            return [
                dict(r)
                for r in db.execute(
                    "SELECT stage_id, boarded_at FROM reports"
                    " WHERE route_id=? AND direction=? AND boarded_at>=?",
                    (route_id, direction, since.astimezone(UTC).isoformat()),
                ).fetchall()
            ]


def _now():
    return datetime.now(UTC).isoformat()
