"""Server-rendered administration views. No external fonts or assets required."""

import html
import json


def escape(value):
    return html.escape(str(value), quote=True)


def icon(name, size=20):
    paths = {
        "grid": '<rect x="3" y="3" width="7" height="7" rx="2"/><rect x="14" y="3" width="7" height="7" rx="2"/><rect x="3" y="14" width="7" height="7" rx="2"/><rect x="14" y="14" width="7" height="7" rx="2"/>',
        "data": '<ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v7c0 4 16 4 16 0V5M4 12v7c0 4 16 4 16 0"/>',
        "model": '<path d="m12 3 9 5-9 5-9-5 9-5Zm-9 9 9 5 9-5M3 17l9 5 9-5"/>',
        "route": '<circle cx="6" cy="5" r="2"/><circle cx="18" cy="19" r="2"/><path d="M8 5h7a4 4 0 0 1 0 8H9a3 3 0 0 0 0 6h7"/>',
        "clock": '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
        "arrow": '<path d="M5 12h14m-5-5 5 5-5 5"/>',
        "refresh": '<path d="M20 7v5h-5M4 17v-5h5M6 6a8 8 0 0 1 13 3M5 15a8 8 0 0 0 13 3"/>',
        "external": '<path d="M14 3h7v7M21 3 10 14M10 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-5"/>',
        "logout": '<path d="M9 4H5a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h4m6-12 4 4-4 4M8 12h11"/>',
        "check": '<path d="m5 12 4 4L19 6"/>',
        "play": '<path d="m9 5 11 7-11 7V5Z"/>',
        "shield": '<path d="m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6l8-3Z"/><path d="m8 12 3 3 5-6"/>',
        "sidebar": '<rect x="3" y="4" width="18" height="16" rx="3"/><path d="M9 4v16"/>',
        "chevron": '<path d="m9 5 7 7-7 7"/>',
    }
    return (
        f'<svg width="{size}" height="{size}" viewBox="0 0 24 24" fill="none" '
        'stroke="currentColor" stroke-width="1.65" stroke-linecap="round" '
        f'stroke-linejoin="round" aria-hidden="true">{paths[name]}</svg>'
    )


CSS = """
:root{color-scheme:light dark;--bg:#f7f8fa;--surface:#fff;--sidebar:rgba(236,239,242,.83);
--text:#22272d;--secondary:#66717d;--muted:#7b8691;--line:#e5e8ec;--accent:#08785c;
--accent-soft:#e6f3ee;--selected:#dcebe5;--shadow:0 2px 5px #1d2c3b03,0 0 0 1px #182c4307;
--inset:#f6f8f9;--amber:#946400;--amber-soft:#fff5de;--red:#b33535}
*{box-sizing:border-box}html{scroll-behavior:smooth;scroll-padding-top:95px}
body{margin:0;background:var(--bg);color:var(--text);font:14px/1.5 -apple-system,
BlinkMacSystemFont,"Segoe UI",sans-serif;-webkit-font-smoothing:antialiased}
a{color:inherit;text-decoration:none}button,input{font:inherit}button,a,input,summary{-webkit-tap-highlight-color:transparent}
button{cursor:pointer}button:disabled{cursor:not-allowed;opacity:.48}
:focus-visible{outline:3px solid #209a79;outline-offset:4px}svg{flex-shrink:0;vertical-align:middle}
.skip{position:fixed;top:-80px;left:20px;z-index:20;padding:10px;background:var(--surface)}
.skip:focus{top:10px}.app{min-height:100vh}.sidebar{width:230px;position:fixed;inset:0 auto 0 0;
background:var(--sidebar);border-right:1px solid var(--line);backdrop-filter:blur(24px);z-index:5;
display:flex;flex-direction:column;padding:30px 16px 18px}
.brand{display:flex;align-items:center;gap:11px;padding:0 10px 34px}.brand-icon{width:38px;height:38px;
display:grid;place-items:center;color:white;background:linear-gradient(150deg,#1a9875,#086149);
box-shadow:inset 0 1px 0 #ffffff38,0 3px 6px #08785c16;border-radius:11px}
.brand-name{font-size:17px;font-weight:680;letter-spacing:-.6px}.brand-sub{font-size:11px;color:var(--secondary)}
.nav-label{font-size:10px;font-weight:650;letter-spacing:1.2px;color:var(--muted);padding:0 12px;margin:15px 0 9px}
.nav-item{display:flex;align-items:center;gap:11px;padding:10px 12px;margin:3px 0;border-radius:8px;
color:var(--secondary);font-size:13px}.nav-item svg{color:var(--accent)}.nav-item:hover{background:#7d8c9810}
.nav-item[aria-current]{background:var(--selected);color:var(--text);font-weight:600}
.nav-item .nav-trail{margin-left:auto;color:var(--muted);width:13px}
.sidebar-bottom{margin-top:auto;padding:24px 8px 0}.environment{display:flex;gap:7px;align-items:center;
font-size:11px;color:var(--secondary);padding:0 4px 20px}.dot{width:6px;height:6px;border-radius:50%;background:#199a70;
box-shadow:0 0 0 3px #1b987018;display:inline-block;flex-shrink:0}
.account{border-top:1px solid var(--line);padding-top:17px;display:flex;align-items:center;gap:10px}
.avatar{width:31px;height:31px;border:1px solid var(--line);border-radius:50%;display:grid;place-items:center;
background:var(--surface);font-size:12px;font-weight:600;color:var(--secondary)}
.account-name{font-size:12px;font-weight:600}.account-role{font-size:10px;color:var(--muted)}
.account form{margin-left:auto}.icon-button{border:0;background:none;color:var(--secondary);padding:7px;
border-radius:6px;display:inline-flex;align-items:center;justify-content:center}.icon-button:hover{background:#7d8c9818}
.workspace{margin-left:230px}.toolbar{height:68px;position:sticky;top:0;z-index:4;
background:color-mix(in srgb,var(--bg) 87%,transparent);backdrop-filter:blur(20px);
border-bottom:1px solid var(--line);display:flex;align-items:center;justify-content:space-between;padding:0 40px}
.breadcrumb{display:flex;gap:14px;align-items:center;color:var(--secondary);font-size:12px}
.breadcrumb span:last-child{color:var(--text);font-weight:550}.breadcrumb svg{opacity:.55}
.toolbar-actions{display:flex;align-items:center;gap:15px}.mobile-logout{display:none}.toolbar-label{color:var(--muted);font-size:11px}
.mobile-brand{display:none}.content{max-width:1390px;margin:auto;padding:39px 40px 28px}
.page-heading{display:flex;align-items:center;justify-content:space-between;gap:20px;margin-bottom:29px}
.eyebrow{font-size:10px;letter-spacing:1.6px;color:var(--muted);font-weight:650;margin:0 0 8px}
h1{font-size:31px;letter-spacing:-1.2px;font-weight:660;line-height:1.2;margin:0 0 9px}
.subtitle{color:var(--secondary);font-size:13px;margin:0}.status-pill{display:inline-flex;align-items:center;
gap:7px;background:var(--surface);border:1px solid var(--line);border-radius:20px;padding:7px 12px;font-size:11px;white-space:nowrap}
.stats{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:16px;margin-bottom:32px}
.stat{border-radius:12px;background:var(--surface);box-shadow:var(--shadow);padding:19px 20px 16px}
.stat-top{display:flex;align-items:center;justify-content:space-between;font-size:11px;color:var(--secondary)}
.stat-top svg{color:var(--muted);width:17px}.stat-value{font-size:31px;line-height:1.3;font-weight:630;
letter-spacing:-1px;margin:10px 0 5px;font-variant-numeric:tabular-nums}
.stat-note{font-size:10px;color:var(--muted)}.stat-value.word{font-size:23px;letter-spacing:-.5px;padding:5px 0}
.section-title{display:flex;align-items:center;justify-content:space-between;margin:28px 0 13px;gap:16px}
h2{font-size:15px;letter-spacing:-.25px;font-weight:650;margin:0}.section-caption{font-size:11px;color:var(--muted)}
.two-col{display:grid;grid-template-columns:minmax(0,1.25fr) minmax(290px,1fr);gap:20px}
.panel{background:var(--surface);border-radius:13px;box-shadow:var(--shadow);overflow:hidden;min-width:0}
.panel-heading{padding:20px 22px 17px;border-bottom:1px solid var(--line);display:flex;align-items:center;gap:10px}
.panel-heading>svg{color:var(--accent);width:18px}.panel-heading h3{font-size:13px;font-weight:600;margin:0}
.panel-heading .tag{margin-left:auto}.tag{font-size:10px;color:var(--secondary);background:var(--inset);
border:1px solid var(--line);border-radius:5px;padding:3px 7px;white-space:nowrap}
.tag.success{background:var(--accent-soft);border-color:transparent;color:var(--accent)}
.tag.amber{background:var(--amber-soft);border-color:transparent;color:var(--amber)}
.panel-body{padding:20px 22px}.checklist{list-style:none;padding:0;margin:0}.checklist li{display:flex;
gap:12px;align-items:center;padding:14px 0;border-bottom:1px solid var(--line)}.checklist li:first-child{padding-top:0}
.checklist li:last-child{border-bottom:0;padding-bottom:0}.step-icon{width:27px;height:27px;border-radius:50%;
display:grid;place-items:center;background:var(--inset);border:1px solid var(--line);color:var(--muted);font-size:11px}
.step-icon.done{background:var(--accent-soft);border-color:transparent;color:var(--accent)}
.row-title{font-size:12px;font-weight:550;margin:0 0 2px}.row-description{font-size:11px;color:var(--secondary);margin:0}
.checklist .row-count{margin-left:auto;color:var(--muted);font-size:11px;white-space:nowrap}
.panel-foot{border-top:1px solid var(--line);padding:12px 22px;background:var(--inset);color:var(--secondary);font-size:10px}
.info-line{display:flex;align-items:flex-start;gap:8px;line-height:1.6}.info-line svg{margin-top:2px;color:var(--muted)}
.empty-state{padding:29px 22px 25px;text-align:center}.empty-glyph{margin:0 auto 14px;width:49px;height:49px;
border-radius:13px;display:grid;place-items:center;color:var(--accent);background:var(--accent-soft)}
.empty-state h3{margin:0 0 7px;font-size:14px;font-weight:620;letter-spacing:-.2px}
.empty-state p{font-size:11px;line-height:1.7;color:var(--secondary);max-width:300px;margin:0 auto}
.model-facts{border-top:1px solid var(--line);padding:15px 22px;display:flex;gap:20px;justify-content:space-between}
.fact-label{font-size:9px;letter-spacing:.6px;text-transform:uppercase;color:var(--muted);margin-bottom:4px}
.fact-value{font-size:11px;font-weight:550}.training-panel{margin-top:0}.training-intro{display:flex;align-items:flex-start;
justify-content:space-between;gap:25px;padding:23px 24px}.training-intro h3{font-size:14px;font-weight:600;margin:0 0 7px}
.training-intro p{font-size:12px;line-height:1.7;color:var(--secondary);max-width:620px;margin:0}
.button{display:inline-flex;align-items:center;justify-content:center;gap:7px;white-space:nowrap;
background:linear-gradient(var(--surface),var(--inset));border:1px solid var(--line);color:var(--text);
padding:8px 13px;border-radius:7px;box-shadow:0 1px 2px #1a26300b;font-size:11px;font-weight:550;min-height:34px}
.button:hover:not(:disabled){filter:brightness(.97)}.button.primary{background:linear-gradient(#128566,#08745a);
border-color:#08745a;color:#fff;box-shadow:inset 0 1px 0 #ffffff25,0 1px 2px #14523d20}
.training-action{text-align:right;flex-shrink:0}.training-action .micro{margin-top:7px;font-size:10px}
.workflow{display:grid;grid-template-columns:repeat(3,1fr);margin:0 24px;padding:18px 0;border-top:1px solid var(--line);gap:24px}
.workflow-item{display:flex;gap:10px}.workflow-number{font-size:10px;font-weight:600;border-radius:50%;width:22px;height:22px;
display:grid;place-items:center;flex-shrink:0;background:var(--inset);border:1px solid var(--line);color:var(--secondary)}
.workflow-item b{font-size:11px;font-weight:550;display:block;margin:2px 0 4px}.workflow-item p{font-size:10px;color:var(--muted);margin:0;line-height:1.6}
.candidate{margin:0 24px 20px;padding:17px 18px;border:1px solid var(--line);border-radius:9px;background:var(--inset)}
.candidate-top{display:flex;justify-content:space-between;align-items:center;gap:15px}.candidate h4{font-size:12px;margin:0 0 3px;font-weight:600}
.candidate p{font-size:11px;color:var(--secondary);margin:0}.candidate form{margin-left:auto}
.candidate .button:disabled{opacity:.4}.metrics{display:grid;grid-template-columns:repeat(3,1fr);gap:15px;margin:17px 0}
.metric strong{font-size:22px;letter-spacing:-.7px;font-weight:600;display:block}.metric span{font-size:10px;color:var(--secondary)}
details{margin-top:13px}summary{cursor:pointer;color:var(--secondary);font-size:11px}pre{font:11px/1.6 ui-monospace,SFMono-Regular,Consolas,monospace;
white-space:pre-wrap;overflow-wrap:anywhere;color:var(--secondary);padding:12px;border:1px solid var(--line);border-radius:6px}
.alert{background:var(--amber-soft);color:var(--amber);padding:12px 18px;font-size:12px;border-radius:8px;margin:0 24px 18px}
.footer{display:flex;justify-content:space-between;gap:14px;color:var(--muted);font-size:10px;padding:22px 0 0}
.footer span{display:flex;align-items:center;gap:6px}.footer a:hover{color:var(--accent)}
.login-page{min-height:100vh;display:grid;place-items:center;background:radial-gradient(ellipse at 20% 0%,#d9eae780,transparent 55%),var(--bg);padding:24px}
.login-card{width:100%;max-width:400px;padding:38px;background:var(--surface);border:1px solid var(--line);border-radius:18px;
box-shadow:0 24px 80px #203e4910}.login-card .brand-icon{margin-bottom:22px;width:48px;height:48px;border-radius:13px}
.login-card h1{font-size:26px}.login-card .subtitle{margin:10px 0 24px}.login-card label{display:block;font-size:12px;font-weight:550;margin:16px 0 7px}
.login-card input:not([type=hidden]){width:100%;border:1px solid var(--line);border-radius:7px;background:var(--inset);color:var(--text);padding:10px 12px}
.login-card .button{width:100%;margin-top:25px;font-size:12px;min-height:38px}.login-note{font-size:10px;color:var(--muted);text-align:center;margin:21px 0 0}
.error{font-size:12px;color:var(--red)}
.evidence-table{width:100%;border-collapse:collapse;margin-top:10px;font-size:11px}
.evidence-table th,.evidence-table td{text-align:left;padding:9px 10px;border:1px solid var(--line)}
.evidence-table th{background:var(--surface);font-weight:600}.evidence-table td:last-child{font-variant-numeric:tabular-nums}
select{background:var(--surface);color:var(--text);border:1px solid var(--line);padding:5px 8px;border-radius:5px;margin-left:5px}
@media(min-width:1450px){.content{padding-top:45px}.stats{gap:20px}.panel-body{padding:24px}.empty-state{padding:35px 24px}}
@media(max-width:1100px){.sidebar{width:205px}.workspace{margin-left:205px}.content{padding:30px 26px}.toolbar{padding:0 26px}
.stat{padding:16px}.stats{gap:12px}.training-intro{gap:16px}.training-intro p{max-width:410px}}
@media(max-width:850px){.sidebar{width:175px;padding-left:10px;padding-right:10px}.workspace{margin-left:175px}
.brand{gap:8px;padding-left:7px}.brand-name{font-size:15px}.brand-icon{width:32px;height:32px}.nav-item{font-size:12px;padding:10px}
.content{padding:28px 22px}.toolbar{padding:0 22px}.two-col{grid-template-columns:1fr}.stats{grid-template-columns:repeat(2,1fr)}
.training-intro{flex-direction:column}.training-action{text-align:left}.training-action .micro{display:inline;margin-left:9px}}
@media(max-width:600px){.sidebar{position:static;width:auto;padding:17px 18px 10px;display:block;border-right:0;border-bottom:1px solid var(--line)}
.brand{padding:0 4px 15px}.brand-name{font-size:17px}.brand-sub{display:none}.sidebar nav{display:flex;gap:5px;overflow:auto}
.nav-label,.sidebar-bottom,.resources{display:none}.nav-item{white-space:nowrap;margin:0;font-size:11px;gap:7px}.nav-item svg{width:16px}
.workspace{margin-left:0}.toolbar{height:50px;padding:0 20px}.toolbar-label{display:none}.mobile-logout{display:block}.content{padding:24px 18px}
h1{font-size:28px}.page-heading{align-items:flex-start;margin-bottom:23px}.page-heading .status-pill{padding:6px 8px;font-size:10px}
.stats{gap:10px;margin-bottom:24px}.stat{padding:15px}.stat-top{font-size:10px}.stat-value{font-size:28px}.stat-note{font-size:9px}
.section-caption{font-size:10px}.panel-heading{padding:17px 18px}.panel-body{padding:18px}.training-intro{padding:20px}
.workflow{grid-template-columns:1fr;gap:13px;margin:0 20px}.workflow-item p{font-size:11px}.candidate{margin:0 20px 20px;padding:15px}
.candidate-top{align-items:flex-start;flex-wrap:wrap}.candidate form{margin-left:0}.metrics{gap:10px}.metric strong{font-size:18px}
.footer{font-size:9px}.login-card{padding:30px}.toolbar-actions{gap:6px}}
@media(prefers-color-scheme:dark){:root{--bg:#1c1e21;--surface:#272a2e;--sidebar:rgba(36,40,43,.92);--text:#edf0f2;
--secondary:#a5aeb7;--muted:#909ba5;--line:#3a3f44;--accent:#64c6a7;--accent-soft:#203f35;--selected:#334b42;
--inset:#23262a;--shadow:0 1px 4px #0002;--amber:#edc36b;--amber-soft:#43391f;--red:#ffabab}
.login-page{background:radial-gradient(ellipse at 20% 0%,#163d3345,transparent 55%),var(--bg)}}
@media(prefers-reduced-motion:reduce){html{scroll-behavior:auto}}
"""


def document(title, body):
    return (
        f'<!doctype html><html lang="en"><head><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width,initial-scale=1">'
        f"<title>{escape(title)}</title><style>{CSS}</style></head><body>{body}</body></html>"
    )


def render_login(token, error=""):
    return document(
        "Sign in · NganyaFlow",
        f'''
    <main class="login-page"><section class="login-card" aria-label="Administrator sign in">
    <div class="brand-icon">{icon("route", 27)}</div><p class="eyebrow">ADMINISTRATION</p>
    <h1>Welcome to NganyaFlow.</h1><p class="subtitle">Sign in to administration.</p>
    <p class="error" role="status">{escape(error)}</p>
    <form method="post" action="/admin/login"><input type="hidden" name="csrf" value="{escape(token)}">
    <label for="username">Username</label><input id="username" name="username" autocomplete="username" value="admin" required>
    <label for="password">Password</label><input id="password" name="password" type="password" autocomplete="current-password" required>
    <button class="button primary">Sign in {icon("arrow", 15)}</button></form>
    <p class="login-note">Your local transport research workspace.</p></section></main>''',
    )


def metric_values(report):
    def value(key, suffix=" min"):
        return f"{float(report[key]):.1f}{suffix}" if key in report else "—"

    coverage = (
        f"{float(report['empirical_coverage']):.0%}" if "empirical_coverage" in report else "—"
    )
    return f"""<div class="metrics"><div class="metric"><strong>{value("mae_minutes")}</strong><span>Median wait error</span></div>
    <div class="metric"><strong>{coverage}</strong><span>Interval coverage</span></div>
    <div class="metric"><strong>{value("mean_interval_width_minutes")}</strong><span>Average interval width</span></div></div>"""


def report_details(report):
    return (
        "<details><summary>View evaluation details</summary><pre>"
        + escape(json.dumps(report, indent=2))
        + "</pre></details>"
    )


def evidence(report):
    if "data_kind" not in report:
        return ""
    rows = [
        ("Quantile Regression Forest", report["mae_minutes"]),
        ("Historical median baseline", report["historical_median_mae_minutes"]),
        ("Last headway baseline", report["last_headway_mae_minutes"]),
    ]
    if "shuffled_label_mae_minutes" in report:
        rows.append(("Shuffled labels control", report["shuffled_label_mae_minutes"]))
    cells = "".join(
        f"<tr><td>{escape(label)}</td><td>{score:.3f} min</td></tr>" for label, score in rows
    )
    curve = report.get("learning_curve", [])
    curve_rows = "".join(
        f"<tr><td>{r['training_days']}</td><td>{r['training_samples']}</td>"
        f"<td>{r['test_mae_minutes']:.3f} min</td></tr>"
        for r in curve
    )
    return f"""<div style="margin-top:20px"><span class="tag amber">{escape(report["data_kind"].upper())} DATA</span>
    <p style="margin:12px 0">{report["input_arrivals"]} arrivals · {report["train_samples"]} training queries ·
    {report["test_samples"]} test queries · {len(report["train_days"])} training days / {len(report["test_days"])} later test days</p>
    <h4>Held-out comparison</h4><table class="evidence-table"><thead><tr><th>Estimator</th><th>Mean absolute error</th></tr></thead><tbody>{cells}</tbody></table>
    <h4 style="margin-top:18px">Learning curve</h4><table class="evidence-table"><thead><tr><th>Training days</th><th>Queries</th><th>Test MAE</th></tr></thead><tbody>{curve_rows}</tbody></table>
    <p style="margin-top:12px">The same held-out days are used for this descriptive learning curve. The shuffled-label run is a negative control, not a significance test.</p>
    <p style="margin-top:12px"><a href="/admin/evaluation.json">Download evaluation JSON</a> · <a href="/admin/predictions.csv">Download held-out predictions</a></p></div>"""


def render_dashboard(
    *,
    counts,
    stages,
    routes,
    report,
    candidate,
    csrf,
    status,
    error,
    days,
    data_kinds=(),
    test_days=1,
):
    token = escape(csrf)
    has_model = bool(report)
    busy = status == "training"
    ready = counts["completed_sessions"] > 0 and days >= 3 + test_days
    train_disabled = (
        ' disabled aria-describedby="training-requirements"' if busy or not ready else ""
    )
    activate_disabled = " disabled" if busy or not candidate else ""
    hidden = f'<input type="hidden" name="csrf" value="{token}">'
    stats = "".join(
        f"""<div class="stat"><div class="stat-top"><span>{label}</span>{icon(glyph)}</div>
        <div class="stat-value{(" word" if isinstance(value, str) else "")}">{escape(value)}</div>
        <div class="stat-note">{note}</div></div>"""
        for label, glyph, value, note in [
            ("Observations", "data", counts["observations"], "Recorded arrival & departure events"),
            (
                "Completed sessions",
                "clock",
                counts["completed_sessions"],
                f"{counts['sessions']} observation sessions in total",
            ),
            ("Configured stages", "route", stages, f"{routes} routes in the pilot catalog"),
            (
                "Active model",
                "model",
                "Ready" if has_model else "Not trained",
                "Quantile Regression Forest",
            ),
        ]
    )
    checklist = ""
    for number, title, description, count, done in [
        (
            1,
            "Verify the pilot catalog",
            "Confirm stage, route and direction coverage.",
            f"{stages} stages",
            bool(stages and routes),
        ),
        (
            2,
            "Collect continuous sessions",
            "Record arrivals and close each observation session.",
            f"{counts['completed_sessions']} complete",
            bool(counts["completed_sessions"]),
        ),
        (
            3,
            "Build enough day coverage",
            "At least 3 training days and 1 later test day.",
            f"{days} days",
            days >= 4,
        ),
    ]:
        checklist += f"""<li><span class="step-icon{" done" if done else ""}">{icon("check", 14) if done else number}</span>
        <div><p class="row-title">{title}</p><p class="row-description">{description}</p></div><span class="row-count">{count}</span></li>"""
    if has_model:
        active_content = f"""<div class="panel-body"><p class="row-title">Model {escape(report.get("model_version", ""))}</p>
        <p class="row-description">Evaluated on later, held-out observation days.</p>{metric_values(report)}{report_details(report)}</div>"""
    else:
        active_content = f"""<div class="empty-state"><div class="empty-glyph">{icon("model", 27)}</div>
        <h3>No active model yet</h3><p>Collect observations, train a candidate, then review its evaluation before activation.</p></div>"""
    if candidate:
        candidate_content = f'''<div class="candidate-top"><div><h4>Candidate ready for review</h4>
        <p>Version {escape(candidate.get("model_version", ""))}</p></div><form method="post" action="/admin/activate">{hidden}
        <button class="button"{activate_disabled}>{icon("check", 14)} Activate reviewed candidate</button></form></div>
        {metric_values(candidate)}{evidence(candidate)}{report_details(candidate)}'''
    else:
        candidate_content = f"""<div class="candidate-top"><div><h4>No candidate trained</h4>
        <p>Your next evaluation will appear here.</p></div><form method="post" action="/admin/activate">{hidden}
        <button class="button" disabled>Activate candidate</button></form></div>"""
    state_label = {
        "idle": "Ready to collect",
        "training": "Training in progress",
        "failed": "Training needs attention",
    }.get(status, "Candidate ready")
    error_html = f'<div class="alert" role="alert">{escape(error)}</div>' if error else ""
    provenance = (
        '<div class="alert" style="margin:0 0 22px"><strong>Synthetic validation workspace</strong>'
        "<br>CBD places are real. Arrival times and route connectivity are simulated. These results do not establish field accuracy.</div>"
        if "synthetic" in data_kinds
        else ""
    )
    return document(
        "NganyaFlow administration",
        f'''
    <a class="skip" href="#main">Skip to content</a><div class="app">
    <aside class="sidebar" aria-label="Workspace sidebar"><a class="brand" href="/admin" aria-label="NganyaFlow home">
    <span class="brand-icon">{icon("route", 24)}</span><span><span class="brand-name">NganyaFlow</span><br><span class="brand-sub">Research workspace</span></span></a>
    <nav aria-label="Main navigation"><p class="nav-label">WORKSPACE</p>
    <a class="nav-item" href="#overview" aria-current="page">{icon("grid", 18)} Overview</a>
    <a class="nav-item" href="/admin/datasets">{icon("data", 18)} Observation datasets</a>
    <a class="nav-item" href="#training">{icon("model", 18)} Model training</a></nav>
    <div class="resources"><p class="nav-label">RESOURCES</p>
    <a class="nav-item" href="/docs">{icon("external", 17)} API documentation</a>
    <a class="nav-item" href="/health">{icon("shield", 18)} Server health</a></div>
    <div class="sidebar-bottom"><div class="environment"><span class="dot"></span> Local workspace</div>
    <div class="account"><span class="avatar">A</span><div><div class="account-name">Administrator</div>
    <div class="account-role">Pilot management</div></div><form method="post" action="/admin/logout">{hidden}
    <button class="icon-button" aria-label="Sign out" title="Sign out">{icon("logout", 17)}</button></form></div></div></aside>
    <div class="workspace"><header class="toolbar"><div class="breadcrumb">{icon("sidebar", 18)}
    <span>Workspace</span>{icon("chevron", 11)}<span>Overview</span></div><div class="toolbar-actions">
    <span class="toolbar-label">Pilot administration</span><a class="button" href="/admin" aria-label="Refresh dashboard">{icon("refresh", 14)} Refresh</a>
    <form class="mobile-logout" method="post" action="/admin/logout">{hidden}<button class="icon-button" aria-label="Sign out" title="Sign out">{icon("logout", 17)}</button></form></div></header>
    <main class="content" id="main"><div class="page-heading" id="overview"><div><p class="eyebrow">YOUR WORKSPACE</p>
    <h1>Overview</h1><p class="subtitle">A clearer picture of your transport research.</p></div>
    <span class="status-pill"><span class="dot"></span> Server online</span></div>
    {provenance}<div class="stats" aria-label="Workspace summary">{stats}</div>
    <div class="section-title" id="observations"><h2>Collected observations</h2><span class="section-caption">One corridor. Three stages.</span></div>
    <div class="two-col"><section class="panel" aria-labelledby="collection-title"><div class="panel-heading">{icon("data")}
    <h3 id="collection-title">Collection readiness</h3><span class="tag">Pilot setup</span></div>
    <div class="panel-body"><ol class="checklist">{checklist}</ol></div><div class="panel-foot"><div class="info-line">{icon("shield", 14)}
    <span>Only completed, continuous sessions contribute to training.</span></div></div></section>
    <section class="panel" aria-labelledby="active-title"><div class="panel-heading">{icon("model")}
    <h3 id="active-title">Active model</h3><span class="tag{" success" if has_model else ""}">{"Active" if has_model else "Not trained"}</span></div>
    {active_content}<div class="model-facts"><div><div class="fact-label">Method</div><div class="fact-value">Quantile Regression Forest</div></div>
    <div><div class="fact-label">Target interval</div><div class="fact-value">80% coverage</div></div></div></section></div>
    <div class="section-title" id="training"><h2>Model training</h2><span class="tag{" amber" if error else ""}">{state_label}</span></div>
    <section class="panel training-panel" aria-label="Candidate training"><div class="training-intro"><div>
    <h3>Turn observations into better estimates.</h3><p>Train on earlier days, evaluate on later days, then decide whether to activate the candidate.</p></div>
    <div class="training-action"><form method="post" action="/admin/train">{hidden}<button class="button primary"{train_disabled}>
    {icon("play", 13)} {"Training…" if busy else "Train from completed sessions"}</button>
    <label style="display:block;margin-top:10px;font-size:11px">Held-out days
    <select name="test_days" aria-label="Held-out days"><option value="1"{" selected" if test_days == 1 else ""}>1 day</option><option value="3"{" selected" if test_days == 3 else ""}>3 days</option><option value="7"{" selected" if test_days == 7 else ""}>7 days</option></select></label></form>
    <p class="micro" id="training-requirements">{"Reload to check progress." if busy else f"Requires at least {3 + test_days} days of completed observations."}</p></div></div>
    {error_html}<div class="workflow"><div class="workflow-item"><span class="workflow-number">1</span><div><b>Train</b><p>Use completed sessions from earlier days.</p></div></div>
    <div class="workflow-item"><span class="workflow-number">2</span><div><b>Evaluate</b><p>Compare error, baselines and interval coverage.</p></div></div>
    <div class="workflow-item"><span class="workflow-number">3</span><div><b>Activate</b><p>Publish a candidate after reviewing the evidence.</p></div></div></div>
    <div class="candidate">{candidate_content}</div></section>
    <footer class="footer"><span>{icon("shield", 13)} Your active model stays unchanged during training.</span>
    <a href="/docs">Explore the API {icon("arrow", 12)}</a></footer></main></div></div>''',
    )
