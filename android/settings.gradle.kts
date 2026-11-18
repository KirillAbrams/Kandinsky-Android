// Выбирает узлы, где сборка ищет дополнения и библиотеки, и подключает приложение.
// Запрещает отдельные адреса в модулях, чтобы источники зависимостей были общими.

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Kandinsky"

include(":app")
