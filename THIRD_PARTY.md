# Сторонние компоненты

Этот файл инвентаризирует vendored-компоненты и ключевые сторонние runtime-зависимости, используемые Persistor. Версии и лицензии ниже приведены по файлам исходного дерева, `app/src/main/assets/THIRD_PARTY_NOTICES.txt` и локальным метаданным Gradle-кэша, если это явно указано. Это перечень источников, а не юридическое заключение.

Собственный код Persistor распространяется по MIT (см. `LICENSE`). MIT-лицензия приложения **не заменяет** лицензии, уведомления и тексты лицензий сторонних компонентов, перечисленных ниже. Существующие `LICENSE` и `app/src/main/assets/THIRD_PARTY_NOTICES.txt` этим изменением не редактировались.

## Vendored native-компоненты

| Компонент и каталог | Зафиксированная версия / ревизия | Upstream | Лицензия по локальному источнику | Где находится текст лицензии |
|---|---|---|---|---|
| **JPEGli** — `app/src/main/cpp/jpegli/` | Upstream commit `031a0077f5799a6041004267fc12b956c1f52a20`, указан в `UPSTREAM_COMMIT.txt` | [google/jpegli](https://github.com/google/jpegli) | BSD 3-Clause согласно `LICENSE` и разделу JPEGli в notices | `app/src/main/cpp/jpegli/LICENSE`; также `app/src/main/assets/THIRD_PARTY_NOTICES.txt` |
| **Highway** — `app/src/main/cpp/jpegli/third_party/highway/` (вложен в JPEGli) | `1.3.0` указан как версия проекта в `CMakeLists.txt`; отдельная ревизия/commit в этом дереве не зафиксирована | [google/highway](https://github.com/google/highway) | Основной проект: Apache 2.0 или BSD 3-Clause на выбор; `LICENSE` также отмечает отдельный файл `hwy/contrib/random/random-inl.h` под CC0 1.0 | `app/src/main/cpp/jpegli/third_party/highway/LICENSE`; также раздел Highway в notices |
| **skcms** — `app/src/main/cpp/jpegli/third_party/skcms/` (вложен в JPEGli) | `README.chromium` указывает `Version: unknown`; точная версия и отдельная ревизия не зафиксированы в этом дереве | [google/skcms](https://github.com/google/skcms) | BSD 3-Clause согласно локальному `LICENSE` | `app/src/main/cpp/jpegli/third_party/skcms/LICENSE`; также раздел skcms в notices |
| **libjpeg-turbo** — `app/src/main/cpp/jpegli/third_party/libjpeg-turbo/` (вложен в JPEGli) | Версия и отдельная ревизия не зафиксированы в этом дереве. Шаблон `jconfig.h.in` содержит подстановочные поля версии, а не конкретную версию | [libjpeg-turbo/libjpeg-turbo](https://github.com/libjpeg-turbo/libjpeg-turbo) | `LICENSE.md` описывает IJG License для унаследованного libjpeg API, Modified BSD 3-Clause и zlib License для SIMD-расширений | `app/src/main/cpp/jpegli/third_party/libjpeg-turbo/LICENSE.md`, `README.ijg`; также соответствующий раздел notices |
| **MozJPEG** — `app/src/main/cpp/third_party/mozjpeg/` | `4.1.1` задано в `CMakeLists.txt`; отдельный upstream commit не зафиксирован в этом дереве | [mozilla/mozjpeg](https://github.com/mozilla/mozjpeg) | `LICENSE.md` содержит roll-up из IJG License, Modified BSD 3-Clause и zlib License (для SIMD-кода); notices включают также IJG README | `app/src/main/cpp/third_party/mozjpeg/LICENSE.md`, `README.ijg`; также разделы MozJPEG в notices |
| **libtiff** — `app/src/main/cpp/third_party/libtiff/` | `4.7.0` в файле `VERSION`; отдельный commit не зафиксирован | [libtiff/libtiff](https://gitlab.com/libtiff/libtiff) | Вендорский текст под заголовком “LibTIFF license” в `LICENSE.md` (разрешительные условия и disclaimer); здесь не назначается отдельный SPDX-идентификатор | `app/src/main/cpp/third_party/libtiff/LICENSE.md`; текст также приведён в notices |
| **Leptonica** — `app/src/main/cpp/third_party/leptonica/` | `1.85.0` указано в `CMakeLists.txt`; отдельный commit не зафиксирован | [DanBloomberg/leptonica](https://github.com/DanBloomberg/leptonica) | `LICENSE.txt` содержит условия распространения и disclaimer; notices воспроизводят этот текст. Отдельный SPDX-идентификатор в проверенных локальных файлах не указан | `app/src/main/cpp/third_party/leptonica/LICENSE.txt`; также раздел Leptonica в notices |
| **jbig2enc** — `app/src/main/cpp/third_party/jbig2enc/` | `0.32` в файле `VERSION`; отдельный commit не зафиксирован | [agl/jbig2enc](https://github.com/agl/jbig2enc) | Apache License 2.0 согласно `COPYING` и notices; `COPYING` также отсылает к `doc/PATENTS` | `app/src/main/cpp/third_party/jbig2enc/COPYING`; полный текст Apache 2.0 включён в notices |

JPEGli commit является ревизией указанного upstream JPEGli subtree. Для вложенных Highway, skcms и libjpeg-turbo отдельные upstream commits/теги не указаны; наличие их файлов внутри snapshot само по себе не используется здесь для вывода об их отдельных ревизиях.

## Runtime-библиотеки из Maven

| Компонент и координаты | Версия | Upstream | Лицензия по локальным источникам | Где находится текст лицензии |
|---|---|---|---|---|
| **JPEG XL Android coder** — `io.github.awxkee:jxl-coder` (`app/build.gradle.kts`) | `2.2.0` | [awxkee/jxl-coder](https://github.com/awxkee/jxl-coder), указан в локальном POM и notices | Локальные POM-метаданные указывают Apache License 2.0 и 3-Clause BSD. `THIRD_PARTY_NOTICES.txt` дополнительно содержит MIT-текст для “JxlCoder source components”; соответствие конкретной лицензии отдельным включённым частям там не расписано | `app/src/main/assets/THIRD_PARTY_NOTICES.txt` (разделы JPEG XL Android coder и MIT License for JxlCoder source components); локальный POM в Gradle-кэше содержит названия лицензий и URL |
| **AndroidX HeifWriter / AvifWriter** — `androidx.heifwriter:heifwriter` (`app/build.gradle.kts`) | `1.2.0-rc02` | Страница релиза из локального POM: [AndroidX HeifWriter](https://developer.android.com/jetpack/androidx/releases/heifwriter#1.2.0-rc02); SCM URL в POM: `https://android.googlesource.com/platform/frameworks/support` | Apache Software License 2.0 согласно локальному POM и записи в notices | Полный текст Apache 2.0 включён в `app/src/main/assets/THIRD_PARTY_NOTICES.txt`; POM метаданные также указывают URL текста |

## Объём инвентаризации и неразрешённые вопросы

Прямые AndroidX/Compose зависимости для интерфейса, хранения, изображений и системной интеграции объявлены в `app/build.gradle.kts` (включая Compose BOM `2024.09.00`), но не перечислены по одной в этом codec-focused инвентаре: они не являются отдельными vendored-кодеками, а notices явно выделяют из AndroidX только HeifWriter/AvifWriter. Аналогично, не составляется полный перечень транзитивных Gradle-зависимостей.

По локальным файлам остаются следующие вопросы, которые не следует заполнять догадками:

- Для Highway, skcms и вложенного libjpeg-turbo нет отдельных зафиксированных upstream commits; у skcms версия прямо обозначена как `unknown`, у libjpeg-turbo нет конкретной версии в имеющихся marker-файлах.
- Для MozJPEG, libtiff, Leptonica и jbig2enc локально видны версии, но не отдельные исходные commits/теги.
- В notices для JXL coder присутствуют несколько текстов лицензий; точное распределение Apache 2.0, BSD 3-Clause и MIT по конкретным бинарным/исходным частям в локальном notice не раскрыто.
- `jbig2enc/COPYING` ссылается на `doc/PATENTS`; этот файл здесь не интерпретируется и патентные вопросы не оцениваются.
- В этой инвентаризации не сделана отдельная проверка лицензий каждой транзитивной AndroidX/Compose и Maven-зависимости.
