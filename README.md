# Neurowerk EEG Reader (Android prototype)

Experimental Android prototype for investigating the proprietary Neurowerk `.EEG` format using the sample supplied in the conversation.

## What it currently does
- Opens a local file using Android's system file picker (no broad storage permission required).
- Checks for the `V3.0` signature.
- Attempts to infer a payload offset from the sample count, channel count and 4-byte sample-size arithmetic.
- Plots a 10-second preview for a selected channel using two candidate layouts: interleaved samples and channel-major blocks.

## Important limitations
This is **not a validated decoder**. The 22-channel, 256 Hz, 32-bit-float interpretation and inferred data offset are hypotheses based on one supplied file's byte count. Channel names, physical calibration, montage/reference, annotations, discontinuities, and sample ordering have not been independently verified against Neurowerk. Do not use plotted waveforms for diagnosis or clinical decisions. No EDF export is included because an unverified conversion could silently corrupt EEG signals. The parser uses a sample-specific fallback and may not work with other Neurowerk versions or recordings.

## Build an APK without Android Studio
This repository includes a GitHub Actions workflow at `.github/workflows/android.yml`.

1. Sign in to GitHub and create a **new, private repository** (recommended because EEG files may contain sensitive patient data).
2. Upload the contents of this project folder to the repository's root. Do **not** upload patient EEG recordings or other patient-identifying data.
3. Open the repository's **Actions** tab. If prompted, enable workflows.
4. Select **Build Android APK** from the workflow list and press **Run workflow**. A push to `main` or `master` also triggers a build.
5. When the run finishes successfully, open that run and scroll to **Artifacts**. Download `NeurowerkEEGReader-debug-apk` and extract it; it contains `app-debug.apk`.
6. Transfer `app-debug.apk` to your Android phone and open it to install. Android may ask you to allow installation from that source. Only install APKs you built from source you trust.

The workflow builds a **debug APK** and stores it as an artifact for 14 days. GitHub account sign-in and repository creation are required; I cannot create or publish a repository in your account from this chat.
