//
//  SteganographyManager.swift
//  OSHI
//
//  __STEGO_DEAD_CODE_REMOVED_2026_11_18__ (audit 2026-11-18, finding 4)
//
//  This file used to hold a 656-line image-LSB `SteganographyManager` that no
//  send or receive path ever called, and `SteganographySettingsView`, whose
//  "Enable Steganography" toggle was wired to nothing while its strings promised
//  "hide messages in images to bypass censorship".
//
//  The real feature now lives in:
//    - StegoV1.swift          — STEGO-v1 format (docs/stego/STEGO-v1.md), shared with Android
//    - PhotoDisguiseView.swift — "Privacy disguise" settings, "Disguise as photo"
//                                sheet (1:1 chat, + panel) and the reveal flow.
//
//  The file is kept (empty) so the Xcode project keeps building without a
//  hand edit removing its reference.
//
