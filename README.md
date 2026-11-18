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

## Проверки и выпуск

Из корня скачанного проекта выполни

    cd android
    ./gradlew :app:lintRelease :app:testReleaseUnitTest :app:assembleRelease :app:bundleStore

Для проверок на отдельном эмуляторе или телефоне выполни

    ./gradlew :app:connectedDebugAndroidTest

Сборка **APK** устанавливается напрямую, комплект **AAB** предназначен для магазина
Параметры подписи перечислены в [настройках сборки](android/app/build.gradle.kts)

## Связанные части проекта

- [Сервер Кандинского](https://github.com/KirillAbrams/Kandinsky-Backend)
- [Приложение для айфона](https://github.com/KirillAbrams/Kandinsky-iOS)

Общий снимок исходников проекта **6572430db4f48ca89ebcf9ba0b1330f9808786ff**
