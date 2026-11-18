# Кандинский для Андроида

Приложение показывает историю чатов, отправляет задания на создание фото и видео и открывает готовые результаты

В ленте уже просмотренный ролик может продолжить воспроизведение с сохранённой позиции

## Как открыть

Открой [папку приложения](android) в **Android Studio**

Нужны **Java 17**, платформа номер 37 и инструменты сборки **36.1.0**

Для запуска подойдёт Андроид 10 и новее

Адрес своего сервера передай свойством **kandinsky.bridgeOrigin**

Для подключения нужен защищённый адрес службы связи с действующим сертификатом

## Карта проекта

| Папка или файл | Что внутри |
| --- | --- |
| [app/build.gradle.kts](android/app/build.gradle.kts) | Версии, настройки подписи, адрес сервера и проверки |
| [AndroidManifest.xml](android/app/src/main/AndroidManifest.xml) | Экраны и разрешения приложения |
| [res](android/app/src/main/res) | Значки, оформление и ресурсы |
| [app](android/app/src/main/kotlin/dev/kirill/kandinsky/chat/app) | Запуск приложения и подключение служб аккаунта |
| [account](android/app/src/main/kotlin/dev/kirill/kandinsky/chat/account) | Вход, регистрация и восстановление пароля |
| [attachments](android/app/src/main/kotlin/dev/kirill/kandinsky/chat/attachments) | Камера, выбор и загрузка исходников |
| [chatlist](android/app/src/main/kotlin/dev/kirill/kandinsky/chat/chatlist) | Поиск, закрепление и архив чатов |
| [ConversationState.kt](android/app/src/main/kotlin/dev/kirill/kandinsky/chat/conversation/ConversationState.kt) | Состояние разговора и доступные действия |
| [network](android/app/src/main/kotlin/dev/kirill/kandinsky/chat/network) | Команды серверу, события и восстановление связи |
| [storage](android/app/src/main/kotlin/dev/kirill/kandinsky/chat/storage) | База телефона, черновик и место чтения |
| [rendering](android/app/src/main/kotlin/dev/kirill/kandinsky/chat/rendering) | Страницы истории и отрисовка при прокрутке |
| [media](android/app/src/main/kotlin/dev/kirill/kandinsky/chat/media) | Загрузка и подготовка фото, проигрывание видео |
| [VideoCoverDiskCache.kt](android/app/src/main/kotlin/dev/kirill/kandinsky/chat/media/VideoCoverDiskCache.kt) | Сохранение кадров просмотренных роликов |
| [results](android/app/src/main/kotlin/dev/kirill/kandinsky/chat/results) | Галерея и полноэкранный просмотр |
| [test](android/app/src/test) | Проверки, которые идут без телефона |
| [androidTest](android/app/src/androidTest) и [fixtures](fixtures) | Проверки на устройстве и тестовые материалы |

[Запуск на Маке и Линуксе](android/gradlew) и [запуск на Виндовс](android/gradlew.bat) передают команду [загрузчику сборки](android/gradle/wrapper/gradle-wrapper.jar). [Параметры загрузчика](android/gradle/wrapper/gradle-wrapper.properties) фиксируют один архив и его контрольную сумму, чтобы разработчик и проверяющий использовали одну версию.

## Проверки и выпуск

Из корня скачанного проекта выполни

    cd android
    ./gradlew :app:lintRelease :app:testReleaseUnitTest :app:assembleRelease :app:bundleStore

Для проверок на отдельном эмуляторе или телефоне выполни

    ./gradlew :app:connectedDebugAndroidTest

Сборка **APK** устанавливается напрямую, комплект **AAB** предназначен для магазина

Параметры подписи перечислены в [настройках сборки](android/app/build.gradle.kts)

## Материалы для проверок

В [перечне файлов](fixtures/real-media/manifest.json) есть авторы, источники, условия использования и контрольные суммы

[Малая копия файла](fixtures/real-media/cached-preview-source.json) описана отдельно

- [Описание большого набора](fixtures/manifest.json) перечисляет каждый файл, его вид, размер и контрольную сумму. По нему проверка замечает подмену или потерю вложения.
- [Исходная тестовая история](fixtures/seed-v1.json) задаёт десять тысяч сообщений, их порядок, подписи и ссылки на файлы.
- [Снимки, ролики и звуковые файлы для проверок](fixtures/media) используются при местной проверке чтения истории. Каждый файл описан в перечне большого набора.
- [Малые копии и форма звука](fixtures/media-previews-v1.json) заполняют местный справочник для показа перед открытием оригинала.
- [Ожидаемые результаты операций с историей](fixtures/oracles/history-operations-v1.json) позволяют сверить порядок строк на обеих платформах с заранее рассчитанным ответом.
- [Подборка для проверки места на диске](fixtures/real-media/cache-working-set.json) задаёт исходники для проверки уборки и предела хранения.
- [Очень маленькие копии для проверок](fixtures/real-media/tiny-preview-probe.json) дают исходные данные для сравнения раскодирования и повторного показа.
- [Образцы для проверки текста](fixtures/real-media/text-raster-samples.json) помогают сопоставить границы текста и его изображение при отрисовке ленты.
- [Перечень движущихся картинок](fixtures/catalogue-media/catalogue.json) описывает три файла рядом. Они позволяют проверить, как окно просмотра работает с таким вложением.

## Значки приложения

Имя файла связывает значок с кнопкой или оформлением. [Разрешение на использование значков](android/app/src/main/res/raw/heroicons_license.txt) входит в приложение.

| Назначение | Файл или папка |
| --- | --- |
| Значок установленного приложения | [Значок](android/app/src/main/res/mipmap-anydpi/kandinsky_launcher.xml) |
| Снять фото | [Камера](android/app/src/main/res/drawable-nodpi/kandinsky_attachment_camera.png) |
| Выбрать снимок для запроса | [Снимок](android/app/src/main/res/drawable-nodpi/kandinsky_attachment_photo.png) |
| Начать новый чат | [Добавить](android/app/src/main/res/drawable-nodpi/kandinsky_drawer_add.png) |
| Открыть архив | [Архив](android/app/src/main/res/drawable-nodpi/kandinsky_drawer_archive.png) |
| Вернуться из архива | [Назад](android/app/src/main/res/drawable-nodpi/kandinsky_drawer_back.png) |
| Выйти из аккаунта | [Выход](android/app/src/main/res/drawable-nodpi/kandinsky_drawer_logout.png) |
| Открыть действия чата или аккаунта | [Действия](android/app/src/main/res/drawable/kandinsky_drawer_more.xml) |
| Найти чат по названию | [Поиск](android/app/src/main/res/drawable-nodpi/kandinsky_drawer_search.png) |
| Узор за сообщениями в чате | [Знак](android/app/src/main/res/drawable-nodpi/kandinsky_evolution.png) |
| Развернуть просмотр | [Развернуть](android/app/src/main/res/drawable-nodpi/kandinsky_result_expand.png) |
| Повторить генерацию | [Повтор](android/app/src/main/res/drawable-nodpi/kandinsky_generation_refresh.png) |
| Отметить выбранную модель | [Отметка](android/app/src/main/res/drawable-nodpi/kandinsky_model_check.png) |
| Обозначить модель видео | [Видео](android/app/src/main/res/drawable-nodpi/kandinsky_model_video.png) |
| Открыть недавний ролик | [Ролик](android/app/src/main/res/drawable-nodpi/kandinsky_recent_video.png) |
| Сохранить результат | [Сохранить](android/app/src/main/res/drawable-nodpi/kandinsky_result_save.png) |
| Передать результат другому приложению | [Поделиться](android/app/src/main/res/drawable-nodpi/kandinsky_result_share.png) |
| Вернуться из полного просмотра загруженного видео | [Закрыть просмотр](android/app/src/main/res/drawable-nodpi/kandinsky_upload_close.png) |
| Отметить закреплённый чат | [Скрепка](android/app/src/main/res/drawable/heroicons_paper_clip.xml) |

Сборки для установки и прошлые версии доступны на [странице выпусков Андроида](https://github.com/KirillAbrams/Kandinsky-Android/releases).

## Связанные части проекта

- [Сервер Кандинского](https://github.com/KirillAbrams/Kandinsky-Backend)
- [Приложение для айфона](https://github.com/KirillAbrams/Kandinsky-iOS)
