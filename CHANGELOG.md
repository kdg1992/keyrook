# Changelog

## [0.5.0](https://github.com/kdg1992/keyrook/compare/v0.4.0...v0.5.0) (2026-09-24)


### Features

* **desktop:** add keyboard navigation, copy shortcuts and richer entry cards ([#26](https://github.com/kdg1992/keyrook/issues/26)) ([7806e4b](https://github.com/kdg1992/keyrook/commit/7806e4bac567d58041ebd77d70cc7b257b3945e0))
* **desktop:** add list/detail layout with read-only entry details ([#30](https://github.com/kdg1992/keyrook/issues/30)) ([be011bf](https://github.com/kdg1992/keyrook/commit/be011bfb429a051b1a1f45a3ac72715d54a37fe3))
* **editor:** validate inputs per field and filter file dialogs ([#25](https://github.com/kdg1992/keyrook/issues/25)) ([8a8f05a](https://github.com/kdg1992/keyrook/commit/8a8f05a8e445dc7939926ab0a5524bab05e70691))
* **security:** make window focus locking configurable ([#24](https://github.com/kdg1992/keyrook/issues/24)) ([67c90ca](https://github.com/kdg1992/keyrook/commit/67c90ca992118c3e84e93f412d4fdca00c2eb994))
* **update:** add opt-in update check ([#29](https://github.com/kdg1992/keyrook/issues/29)) ([89e3c27](https://github.com/kdg1992/keyrook/commit/89e3c27b3a68a28c7a66708107d958566ce8a7f0))


### Bug Fixes

* **security:** address review findings in integrity checks and remembered backups ([#27](https://github.com/kdg1992/keyrook/issues/27)) ([f813f64](https://github.com/kdg1992/keyrook/commit/f813f6403c314136a23620b535e45289da35aa05))

## [0.4.0](https://github.com/kdg1992/keyrook/compare/v0.3.3...v0.4.0) (2026-09-24)


### Features

* **backup:** verify vault and backup integrity ([#20](https://github.com/kdg1992/keyrook/issues/20)) ([0e345e1](https://github.com/kdg1992/keyrook/commit/0e345e121e54d2c1bc9edb89a9bcf06a00033e9f))
* **entries:** confirm trash, purge entries and add list quick actions ([#19](https://github.com/kdg1992/keyrook/issues/19)) ([495bdc4](https://github.com/kdg1992/keyrook/commit/495bdc4b926c64da1a838e013520c46fd98d4793))
* **format:** add explicit schema migration pipeline ([#17](https://github.com/kdg1992/keyrook/issues/17)) ([183ce6e](https://github.com/kdg1992/keyrook/commit/183ce6e88a12d540c799d4911874d8ca9491842a))
* **i18n:** add language selection and complete string catalogs ([#23](https://github.com/kdg1992/keyrook/issues/23)) ([e481ac0](https://github.com/kdg1992/keyrook/commit/e481ac01050146fd69653f4d4d9eaefaf1cc1e18))
* **import:** add KeePass CSV import preset ([#21](https://github.com/kdg1992/keyrook/issues/21)) ([7f8d58c](https://github.com/kdg1992/keyrook/commit/7f8d58c5c948f28a725f6fcd9df5c1d6655591ae))
* **settings:** persist non-secret preferences across restarts ([#18](https://github.com/kdg1992/keyrook/issues/18)) ([be40170](https://github.com/kdg1992/keyrook/commit/be40170d365a73a9598ced6dd14f3e47d54b27f7))

## [0.3.3](https://github.com/kdg1992/keyrook/compare/v0.3.2...v0.3.3) (2026-09-24)


### Bug Fixes

* **release:** keep release notices byte-identical across platforms ([#14](https://github.com/kdg1992/keyrook/issues/14)) ([02ebdd8](https://github.com/kdg1992/keyrook/commit/02ebdd8c47623dc13b389f24ee6d85f520177bdc))

## [0.3.2](https://github.com/kdg1992/keyrook/compare/v0.3.1...v0.3.2) (2026-09-24)


### Bug Fixes

* **release:** build the app image verified before publication ([#12](https://github.com/kdg1992/keyrook/issues/12)) ([d7d1227](https://github.com/kdg1992/keyrook/commit/d7d122780f0c8f274a72db8abed2d86dcbcc5f05))

## [0.3.1](https://github.com/kdg1992/keyrook/compare/v0.3.0...v0.3.1) (2026-09-23)


### Bug Fixes

* **release:** approve reviewed native redistribution inventories ([2faf9e1](https://github.com/kdg1992/keyrook/commit/2faf9e10f4f2257926c8b98e96ece9d98a8ec0f1))

## [0.3.0](https://github.com/kdg1992/keyrook/compare/v0.2.0...v0.3.0) (2026-09-23)


### Features

* add encrypted vault core and CI foundation ([7843a86](https://github.com/kdg1992/keyrook/commit/7843a86dafd99fa610a7dbb4607af5369a3faac5))
* add encrypted vault core and CI foundation ([2ef9e35](https://github.com/kdg1992/keyrook/commit/2ef9e35d31e214a2d9216fa86373ffa5670adf71))
* **backup:** add manual snapshots and session status ([ac6bc5d](https://github.com/kdg1992/keyrook/commit/ac6bc5dbb87ec1470db00b01cd036772ba7353e6))
* **desktop:** configure vault factors and complete editor controls ([09f27d4](https://github.com/kdg1992/keyrook/commit/09f27d4014943f16aabe8cb9cb4979aa78490ce5))
* expand desktop workflows for 0.3.0 ([8e0d4a2](https://github.com/kdg1992/keyrook/commit/8e0d4a24432b1392d64d79ca825c0e4593954230))
* **generator:** validate wordlists and select passphrase separators ([2670538](https://github.com/kdg1992/keyrook/commit/2670538d8bf6ed53ee714aa6dd8819e79d329336))
* **import:** map CSV columns from parsed headers ([afe1955](https://github.com/kdg1992/keyrook/commit/afe19558634466ec6e2672195b5e45f01a58393b))
* integrate desktop vault version 0.2.0 ([695f858](https://github.com/kdg1992/keyrook/commit/695f8589d8a501d15cc45c6b53e211cdafe2715c))
* integrate desktop vault workflows and security controls ([15550e4](https://github.com/kdg1992/keyrook/commit/15550e4587b3828c357db154330b77c438af468f))
* **organization:** edit customer and project assignments ([ef8476a](https://github.com/kdg1992/keyrook/commit/ef8476a53d7c84e3b28c01d4a8c3c23f8981fe0e))
* **search:** add project and expiry filters with sorting ([2de0a44](https://github.com/kdg1992/keyrook/commit/2de0a4423520fefdaf36844d4df7e75e161c619a))
* **ssh:** authenticate PuTTY PPK imports ([3f1ca99](https://github.com/kdg1992/keyrook/commit/3f1ca999992371d71a46dc1150af86a0e5797078))


### Bug Fixes

* **security:** preserve clipboard ownership and expired locks ([c8ec2ae](https://github.com/kdg1992/keyrook/commit/c8ec2ae1bfe418e8833f9cfcc8af2d7700be1cdb))

## [0.2.0](https://github.com/kdg1992/keyrook/compare/v0.1.0...v0.2.0) (2026-09-23)


### Features

* Add the desktop vault editor, search, keyboard shortcuts and all eight entry types.
* Add password and SSH key generation, encrypted backups, restore and import/export.
* Add automatic locking, clipboard expiry, unlock delays and credential warnings.
* Prepare native packaging and reproducible license inventory collection; installer distribution remains blocked pending review.

### Build tools

* Update Gradle to 9.7.1 and JUnit to 6.1.3.

### Core foundation

* add encrypted vault core and CI foundation ([7843a86](https://github.com/kdg1992/keyrook/commit/7843a86dafd99fa610a7dbb4607af5369a3faac5))
* add encrypted vault core and CI foundation ([2ef9e35](https://github.com/kdg1992/keyrook/commit/2ef9e35d31e214a2d9216fa86373ffa5670adf71))
