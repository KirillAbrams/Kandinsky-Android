# Кандинский для Android

Приложение для создания фото и видео. История чатов доступна на обоих устройствах через сервер в Алматы.

## Запуск

Установи JDK 17 и Android Studio Panda 3 2025.3.3 Patch 1 или новее. В Android Studio открой SDK Manager → SDK Tools и добавь Android SDK Command-line Tools. При нестандартном расположении SDK укажи путь в `ANDROID_HOME`. На Линуксе эмулятору нужна аппаратная виртуализация.

На Mac или Линуксе запусти команду из корня проекта.

```sh
./scripts/run-review.sh
```

Команда скачает нужные компоненты SDK, создаст эмулятор Pixel 7 с Android 17, соберёт и откроет приложение. Подтверди лицензию SDK, если появится запрос. Пока эмулятор работает, оставь терминал открытым.

Для телефона с Android 10 или новее можно скачать готовую сборку. [Выпуски Android](https://github.com/KirillAbrams/Kandinsky-Android/releases)

## Вход для проверяющего

- **Почта** kandinsky-demo@umbrellax.io
- **Пароль** KandinskyVolna27!

Открой «Плотную медиаленту» и «Длинную историю». Пролистай каждый чат, открой фото и видео. История тестового аккаунта общая. Для проверки регистрации используй свою почту.

## Как проверить генерацию

1. Создай отдельный чат. Выбери Image Lite, опиши фото и отправь запрос. Открой результат, вернись в чат и нажми «Сохранить» под фото. Файл появится в папке Kandinsky внутри загрузок.
2. Выбери Video Lite. Для короткой проверки поставь 384 × 384 и 1 с. Опиши видео, отправь запрос и проиграй готовый ролик.
3. Нажми «Добавить фото или видео» и выбери «Оживить фото из галереи». Возьми сохранённый снимок из недавних файлов или загрузок, опиши движение и проиграй результат.
4. На втором устройстве войди в тот же аккаунт и открой этот чат. Отправь запрос по очереди с каждого устройства и проверь, что на другом видны прогресс и готовый файл.

Первый запуск модели может занять несколько минут.

## Карта проекта

- Запуск приложения и экраны входа, регистрации и восстановления пароля. [app](android/app/src/main/kotlin/dev/kirill/kandinsky/chat/app) и [account](android/app/src/main/kotlin/dev/kirill/kandinsky/chat/account)
- Список чатов, экран разговора и прокрутка истории. [chatlist](android/app/src/main/kotlin/dev/kirill/kandinsky/chat/chatlist), [conversation](android/app/src/main/kotlin/dev/kirill/kandinsky/chat/conversation) и [rendering](android/app/src/main/kotlin/dev/kirill/kandinsky/chat/rendering)
- Загрузка исходников, подготовка файлов и просмотр результатов. [attachments](android/app/src/main/kotlin/dev/kirill/kandinsky/chat/attachments), [media](android/app/src/main/kotlin/dev/kirill/kandinsky/chat/media) и [results](android/app/src/main/kotlin/dev/kirill/kandinsky/chat/results)
- Связь с сервером, база на телефоне и черновики. [network](android/app/src/main/kotlin/dev/kirill/kandinsky/chat/network) и [storage](android/app/src/main/kotlin/dev/kirill/kandinsky/chat/storage)
- Проверки логики и проверки на устройстве. [test](android/app/src/test) и [androidTest](android/app/src/androidTest)

[Сервер Кандинского](https://github.com/KirillAbrams/Kandinsky-Backend) · [Приложение для iOS](https://github.com/KirillAbrams/Kandinsky-iOS)
