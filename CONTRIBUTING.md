# Contributing

Thanks for helping improve SurfSave. Keep changes small, testable, and aligned with the existing Android Views/DataBinding architecture unless a task explicitly plans a migration.

## Development Setup

Requirements:

- JDK 21.
- Android SDK.
- Android NDK `27.3.13750724` for full native proxy builds.
- Go for full native proxy builds.

Fast verification for most Kotlin/UI work:

```powershell
.\gradlew.bat --console=plain -PSKIP_GO_BUILD=true testDiagnosticUnitTest assembleDiagnostic lintDiagnostic
```

Full diagnostic APK export with native proxy libraries:

```powershell
.\gradlew.bat --console=plain exportDiagnosticApks
```

## Project Boundaries

- Keep `applicationId = "com.surfsave.browser"`.
- Keep `namespace = "com.myAllVideoBrowser"` unless a dedicated namespace migration is planned.
- Do not commit `local.properties`, keystores, APKs, crash logs, debug screenshots, cookie files, or local AI workflow files.
- Do not add analytics, ads, or project-operated telemetry without an explicit privacy review.
- Do not silently swallow download or detection errors; log structured details and show user-facing explanations where appropriate.

## Pull Request Checklist

- Explain the user-visible behavior change.
- Mention affected modules and data migrations, if any.
- Add focused tests for new logic.
- Run the fast verification command above.
- For download queue, migration, proxy, or browser lifecycle changes, include manual verification notes.

## Coding Notes

- Prefer existing repositories, managers, and ViewModel patterns before adding new abstractions.
- Use structured parsers and Android APIs instead of ad-hoc string manipulation when available.
- Keep release signing and native dependency updates as separate, explicit tasks.
