# Changelog

## [0.8.1](https://github.com/kdg1992/keyrook/compare/v0.8.0...v0.8.1) (2026-09-25)


### Bug Fixes

* **app:** improve unlock, list performance, errors, dialogs and wording ([#60](https://github.com/kdg1992/keyrook/issues/60)) ([bfa4978](https://github.com/kdg1992/keyrook/commit/bfa49785bd3bcfd5ae98aaf60d3f3346cb943089))
* **packaging:** allow the desktop app half of physical memory ([#63](https://github.com/kdg1992/keyrook/issues/63)) ([129b1fb](https://github.com/kdg1992/keyrook/commit/129b1fb0bd5f9e9010c63a2eb636f645d0a860f5))
* raise vault size limits, remap imported IDs and fix imported entry quick copy ([#62](https://github.com/kdg1992/keyrook/issues/62)) ([b153b20](https://github.com/kdg1992/keyrook/commit/b153b20b24310d9c1f92b066a6029149dc14d4f7))

## [0.8.0](https://github.com/kdg1992/keyrook/compare/v0.7.0...v0.8.0) (2026-09-24)


### Features

* **format:** add vault document schema 2 with customer details, pinning and templates ([#57](https://github.com/kdg1992/keyrook/issues/57)) ([0165d6c](https://github.com/kdg1992/keyrook/commit/0165d6cf8e81d275557e308552a11509b2bebca6))
* **health:** add consent-gated breach check against Pwned Passwords ([#55](https://github.com/kdg1992/keyrook/issues/55)) ([64a576f](https://github.com/kdg1992/keyrook/commit/64a576f3a3c2df6b7bbe4c7b8559ccbaf2ffe88a))

## [0.7.0](https://github.com/kdg1992/keyrook/compare/v0.6.1...v0.7.0) (2026-09-24)


### Features

* **a11y:** add interface scaling, high contrast and screen reader labels ([#52](https://github.com/kdg1992/keyrook/issues/52)) ([565364f](https://github.com/kdg1992/keyrook/commit/565364f9099417d57f80529c09a46574ec223e2b))
* **generator:** add presets, ambiguous character exclusion and remembered choices ([#48](https://github.com/kdg1992/keyrook/issues/48)) ([863731d](https://github.com/kdg1992/keyrook/commit/863731ded6f069173e4e09c44c7b4307a9117261))
* **health:** report old passwords and possible duplicate entries ([#49](https://github.com/kdg1992/keyrook/issues/49)) ([aa1d440](https://github.com/kdg1992/keyrook/commit/aa1d4405a744248eb509d29a328f6eea8f1516bc))
* **list:** add bulk actions, favorites and recently used entries ([#51](https://github.com/kdg1992/keyrook/issues/51)) ([785b456](https://github.com/kdg1992/keyrook/commit/785b4567150f8a83edafd58e1b607c963d1d7765))
* **reports:** add customer overview, handover sheet and expiry export ([#50](https://github.com/kdg1992/keyrook/issues/50)) ([e806040](https://github.com/kdg1992/keyrook/commit/e806040d03b5c7b695d93fca82a8ec704aff20b6))


### Bug Fixes

* **app:** harden unlock, saving, closing, trash, key files and CSV errors ([#45](https://github.com/kdg1992/keyrook/issues/45)) ([881ced0](https://github.com/kdg1992/keyrook/commit/881ced0ea6ac4cd18072d825140c30584df60b78))

## [0.6.1](https://github.com/kdg1992/keyrook/compare/v0.6.0...v0.6.1) (2026-09-24)


### Bug Fixes

* **backup:** harden backup rotation, permissions and copy identity ([#40](https://github.com/kdg1992/keyrook/issues/40)) ([bb6659d](https://github.com/kdg1992/keyrook/commit/bb6659d7eae65e5a2f7ff845382c77e0366428ab))
* **security:** avoid string copies of secrets during search and health scans ([#41](https://github.com/kdg1992/keyrook/issues/41)) ([b53bb63](https://github.com/kdg1992/keyrook/commit/b53bb6380d35c15627978b9f7361dd55f9f6def9))

## [0.6.0](https://github.com/kdg1992/keyrook/compare/v0.5.0...v0.6.0) (2026-09-24)


### Features

* **desktop:** remember window placement and add application icon ([#33](https://github.com/kdg1992/keyrook/issues/33)) ([d5745b5](https://github.com/kdg1992/keyrook/commit/d5745b5221d971b134c32efc9b8bd8ecd9460cba))
* **desktop:** replace Swing message dialogs with themed Compose dialogs ([#36](https://github.com/kdg1992/keyrook/issues/36)) ([11813a9](https://github.com/kdg1992/keyrook/commit/11813a9cf56593733a939640f1b4daebaaf2599a))
* **totp:** generate one-time codes from stored secrets ([#35](https://github.com/kdg1992/keyrook/issues/35)) ([f1f56e7](https://github.com/kdg1992/keyrook/commit/f1f56e7ff586408f955719f3780f0655986aa0fd))


### Bug Fixes

* **packaging:** test native installers and keep DEB installable without a menu directory ([#32](https://github.com/kdg1992/keyrook/issues/32)) ([f10100a](https://github.com/kdg1992/keyrook/commit/f10100a427f593578a6e2e0409bedc250f18df68))
* **security:** lock after system suspend even when the monotonic clock paused ([#34](https://github.com/kdg1992/keyrook/issues/34)) ([1b9f7bf](https://github.com/kdg1992/keyrook/commit/1b9f7bf72af52d37aeeb7761548fbcea207856e2))

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
