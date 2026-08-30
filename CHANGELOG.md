# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Initial project scaffolding and baseline architecture for PaperMC 1.21+ (Java 21).
- Dynamic progression gating engine for Nether and The End portals.
- In-game `/progress` (`/asr progress`) survival milestone tracker card.
- Idle / standing-still progression reminder engine with configurable cooldowns.
- Journey Guide Book system (`/asr book`).
- Multi-dragon boss party scaling with configurable multiplier and rounding modes (`HALF_UP`, `CEIL`, `FLOOR`).
- Anti-cheese protections (blocking Bed/Anchor explosion damage on Ender Dragon and Wither, exit portal crystal placement blocking).
- Standards-compliant CI workflows for DCO, OpenSSF Scorecard, and Standards validation.
