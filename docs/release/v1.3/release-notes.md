# Schism 1.3.0

Schism 1.3 focuses on private receipt capture and launch reliability.

Receipt photos can now be read on-device with PaddleOCR. The small verified model is downloaded only after you choose to scan, can wait for Wi-Fi, resumes safely, and works offline after installation. Photos and extracted receipt text are not uploaded for OCR.

Bank-message import remains optional and disabled on a fresh install. Schism explains the feature before requesting Android SMS permission, processes supported transaction alerts locally, and provides separate controls to disable import, revoke permission, or delete locally imported transaction suggestions.

This release also strengthens account/session security, backend authorization, local token storage, database upgrade safety, Android 16 compatibility, release packaging, and accessibility-oriented onboarding.

Known launch notes:

- Receipt OCR quality varies with image crop, lighting, focus, and receipt layout; every result remains editable.
- Live Split, manual bill entry, receipt capture, balances, and existing history remain usable without enabling SMS import.
- The GitHub APK targets modern 64-bit Android devices. Google Play provides the appropriate optimized package for eligible devices.
