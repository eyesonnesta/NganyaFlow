import json
import sqlite3
from contextlib import contextmanager
from pathlib import Path


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

    def add_batch(self, records):
        from datetime import datetime

        accepted, duplicates = [], []
        with self.connect() as db:
            for record in records:
                ident, payload = str(record.id), record.model_dump_json()
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
                start = datetime.fromisoformat(json.loads(session["payload"])["started_at"])
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
                ident, payload = str(event.id), event.model_dump_json()
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
