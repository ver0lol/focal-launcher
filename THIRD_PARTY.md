# Upstream и лицензии

| Компонент | Происхождение / проверка |
| --- | --- |
| Android Platform 30 / Build Tools 35.0.0 | Официальные архивы Google; Host URL и SHA-256 закреплены в `scripts/toolchain.py`; Linux x86_64 и macOS выбираются отдельно. Архивы сверены с официальным repository2-1.xml Google (Build Tools 35.0.0 / Platform 30 r03). SDK остаётся вне Git. |
| SFA / sing-box 1.14.4 ARM32 | [Официальный релиз](https://github.com/SagerNet/sing-box/releases/tag/v1.14.4). APK SHA-256 `b1ae76164e1f518c0ae343ce30d8ee63fbb46a59f1e12295bf5bd20f1e1a5e1b`. |
| SFA overlay | Добавленные исходники в `singbox-integration/`; GPL-3.0-or-later согласно существующему уведомлению. Полный текст — `singbox-integration/COPYING`, уведомление upstream — `singbox-integration/UPSTREAM-LICENSE.txt`. |
| Исходники SFA/core | Архивы в `singbox-integration/upstream/`; это source archives, а не конфигурации или APK. SFA commit `f87c2ebdb7e34754be751bb5e58926db2fed8f8c` из submodule релиза 1.14.4. |
| TizenTube Cobalt 2.0.3 | [Официальный исходный проект](https://github.com/reisxd/TizenTubeCobalt). Установленный APK не модифицирован; новое имя — alias в focal-launcher. Код приложения не включён в этот репозиторий. |

Не приписывайте локальную сборку Focal VPN авторам sing-box/SagerNet. Сборка overlay проверяет неизменность всех upstream native-библиотек. Не коммитьте скачанные APK, vendor firmware, аккаунты или ключи. При дальнейшем распространении соблюдайте лицензии соответствующих компонентов.

Контрольные суммы сохранённых source archives находятся в `singbox-integration/upstream/SHA256SUMS` и проверяются командой `make check`.
