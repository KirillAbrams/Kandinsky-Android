# Кандинский для Android

Открой эту папку в Android Studio. Установи платформу Android 36 и инструменты сборки 36.1.0. Выбери приложение app и устройство с Android 10 или новее, затем нажми кнопку запуска. Для сборки из терминала понадобится Java 17.

```sh
./gradlew :app:assembleDebug
```

Приложение подключается по HTTPS к службе связи в Алматы. Адрес задаёт свойство сборки `kandinsky.bridgeOrigin`. Для проверки на телефоне войди в существующий аккаунт. Вариант store предназначен для выпуска. Ключ подписи передаётся отдельно.

Перейди к [форме входа](app/src/main/kotlin/dev/kirill/kandinsky/chat/account), [экрану разговора](app/src/main/kotlin/dev/kirill/kandinsky/chat/conversation) или [списку чатов](app/src/main/kotlin/dev/kirill/kandinsky/chat/chatlist). [Сеть](app/src/main/kotlin/dev/kirill/kandinsky/chat/network) отвечает за обмен с сервером, [хранилище](app/src/main/kotlin/dev/kirill/kandinsky/chat/storage) сохраняет данные на телефоне. В Android Studio доступны [проверки без устройства](app/src/test) и [проверки на телефоне](app/src/androidTest).

Выбор файла, загрузка на сервер и повторный выбор из недавних собраны в [ChatAttachmentCoordinator.kt](app/src/main/kotlin/dev/kirill/kandinsky/chat/attachments/ChatAttachmentCoordinator.kt). Полноэкранный просмотр и переход к соседнему файлу находятся в [разделе результатов](app/src/main/kotlin/dev/kirill/kandinsky/chat/results).

Для проверки восстановления открой «Длинную историю», набери черновик и поверни телефон. Затем останови приложение через его настройки и открой снова. В обоих случаях проверь выбранный чат, текст и место чтения. Удали черновик после проверки. При открытии готового видео данные приходят из Алматы, просмотренные части сохраняются на телефоне.

## Как проверить библиотеки сборки

Из этой папки выполни `./gradlew :app:dependencies --configuration releaseRuntimeClasspath > /tmp/kandinsky-libs.txt`. Затем запусти `python3 ../scripts/check-runtime-deps.py --android-report /tmp/kandinsky-libs.txt`. Проверка найдёт библиотеки сети и передачи файлов, а также покажет, если в приложение попадут библиотеки для тестов.

## Данные на телефоне

Учётные данные шифруются ключом из Android Keystore. У каждого аккаунта своя папка истории и черновиков. После выхода приложение забывает ключ входа текущего аккаунта и сохраняет его местную историю. Удаление аккаунта также удаляет эту папку и временные копии для отправки в другие приложения. Файлы, которые пользователь сам сохранил в «Загрузки» или передал другому приложению, остаются у получателя. Резервное копирование отключено. В выпускной сборке есть отдельные исключения для облака и переноса на другой телефон.

Приложение использует HTTPS с системной проверкой сертификата и имени сервера. Открытый HTTP запрещён. Системная проверка позволяет обслуживать установленные приложения после плановой замены ключа сертификата. Отдельная привязка к ключу потребовала бы заранее выпустить запасной ключ и доставить обновление на телефоны до его замены. [Рекомендация Android](https://developer.android.com/privacy-and-security/security-config#pin-certificates)

## Где менять синхронизацию

За повтор одного результата отвечает [SyncGenerationActions](app/src/main/kotlin/dev/kirill/kandinsky/chat/network/SyncGenerationActions.kt). Там видно, какой номер попытки телефон отправит после потери ответа. Исходящие сообщения ждут подтверждения в [SyncOutbox](app/src/main/kotlin/dev/kirill/kandinsky/chat/network/SyncOutbox.kt), входящие события разбирает [SyncIncomingEvents](app/src/main/kotlin/dev/kirill/kandinsky/chat/network/SyncIncomingEvents.kt). Страницы истории загружает [SyncHistory](app/src/main/kotlin/dev/kirill/kandinsky/chat/network/SyncHistory.kt). Сеанс соединения и его восстановление находятся в [SyncConnection](app/src/main/kotlin/dev/kirill/kandinsky/chat/network/SyncConnection.kt).

За то, какое сообщение остаётся на экране после подгрузки или смены размера текста, отвечает [ChatReadingPosition](app/src/main/kotlin/dev/kirill/kandinsky/chat/rendering/ChatReadingPosition.kt). Загрузку страниц ищи в [ChatHistoryPages](app/src/main/kotlin/dev/kirill/kandinsky/chat/rendering/ChatHistoryPages.kt), отрисовку видимых строк в [ChatFrameRendering](app/src/main/kotlin/dev/kirill/kandinsky/chat/rendering/ChatFrameRendering.kt).

В базе телефона [NetworkEventWrites](app/src/main/kotlin/dev/kirill/kandinsky/chat/network/NetworkEventWrites.kt) применяет события сервера, [NetworkMessageMerge](app/src/main/kotlin/dev/kirill/kandinsky/chat/network/NetworkMessageMerge.kt) связывает полученное сообщение с локальной записью. [NetworkGenerationWrites](app/src/main/kotlin/dev/kirill/kandinsky/chat/network/NetworkGenerationWrites.kt) сохраняет номер повтора и удаляет его после записи ответа.
