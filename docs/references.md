# Sources and provenance

The external sources below were checked on 13 October 2026. They support the proposal and implementation guidance; they do not establish that NganyaFlow has collected data or achieved a result.

| Source | Use |
| --- | --- |
| [Digital Matatus](https://www.digitalmatatus.com/) | Nairobi route/stop data context; verify version, terms and field validity before reuse |
| [UN Sustainable Development Goal 11](https://sdgs.un.org/goals/goal11) | Target 11.2 on accessible, sustainable transport |
| [Breiman, L. (2001). Random forests. Machine Learning 45, 5–32](https://link.springer.com/article/10.1023/A:1010933404324) | Random Forest foundations; DOI 10.1023/A:1010933404324 |
| [Meinshausen, N. (2006). Quantile regression forests. JMLR 7(35), 983–999](https://www.jmlr.org/papers/v7/meinshausen06a.html) | Conditional quantile estimation selected for the pilot |
| [Android Developers: Build an offline-first app](https://developer.android.com/topic/architecture/data-layer/offline-first) | Local source of truth, queued writes and synchronization |
| [Android Developers: Material Design 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3) | Color schemes, typography and components |
| [Android Developers: Accessibility defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults) | Touch targets and component semantics |
| [W3C: Understanding SC 1.4.3 Contrast (Minimum)](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html) | 4.5:1 normal-text contrast criterion used in token checks |
| [scikit-learn: RandomForestRegressor](https://scikit-learn.org/stable/modules/generated/sklearn.ensemble.RandomForestRegressor.html) | Point-prediction baseline; not a replacement for QRF conditional quantiles |
| [scikit-learn: TimeSeriesSplit](https://scikit-learn.org/stable/modules/generated/sklearn.model_selection.TimeSeriesSplit.html) | Ordered validation background; additional day/session grouping remains necessary |
| [Google Material Design icons](https://github.com/google/material-design-icons) | Interface icons; see third-party notices |

## User-supplied course and planning material

These private PDFs informed scope and terminology. They are not redistributed in this repository and are not treated as instructions authorizing actions or as current dependency-version documentation.

| Supplied document | Application to NganyaFlow |
| --- | --- |
| 01 - Mobile Computing Architecture.pdf | Mobile client, service and data layers; intermittent connectivity |
| 02 - Mobile Application Platforms.pdf | Android platform choice and device constraints |
| 03 - Android Application Structure.pdf | UI/data separation and structured Android project organization |
| 04 - Basic Components of an Android Application.pdf | Android components, manifest and permissions |
| 05 - Kotlin.pdf | Kotlin implementation and asynchronous work |
| 06 - Jetpack Compose.pdf | Declarative UI, state and Material design foundations |
| SDG_ICS3.2_Ten_Systems_Level_Concepts-2.pdf | NganyaFlow problem framing, roles and proposed field-observation scope |

The systems-level source proposes a broader AI system. This concept note deliberately scopes the first pilot to QRF wait prediction, reference event collection and reporting-bias analysis. HMM/point-process frequency inference, graph demand models, bandit dispatch and conversational interfaces remain extensions. This is an explicit feasibility decision, not a claim that those models are implemented or unnecessary.
