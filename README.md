# MobileLinux

Мини-терминал на Java для Android (запускается в JavaDroid, Termux и любой среде с Java 11+).
Ранняя версия — возможны ошибки.

A small terminal written in Java for Android (JavaDroid, Termux, or anything with Java 11+). Early version — bugs are possible.

## Запуск / Run

```
java MobileLinux.java
```

В Termux сначала: `pkg install openjdk-17`.

## Команды / Commands

| Команда | Что делает |
|---|---|
| `creato <файл>` | создаёт файл (любое расширение: `.py`, `.cpp`, `.java`…) и открывает редактор |
| `download pkt python` | устанавливает пакет python (Jython 2.7.4) |
| `start python <файл.py>` | ищет файл и запускает его |
| `python <файл.py>` | запускает Python напрямую |
| `exit` | выход |

## Редактор creato

Внизу экрана всегда есть подсказка.

- **`,,`** (две запятые) — включить режим команд
- в режиме команд: **Q** — сохранить, **X** — выйти, **N** — новый файл
- **`..`** (две точки) — вернуться к тексту
- Работают также стрелки, Home, End, PgUp, PgDn

## Пакет python

`download pkt python` скачивает [Jython](https://www.jython.org/) 2.7.4 с Maven Central
(`repo1.maven.org`). Нужен интернет. Это **Python 2.7** на Java: `print("текст")` работает,
f-строк нет. Если в файле есть не-латинские буквы, первой строкой добавь:

```
# -*- coding: utf-8 -*-
```

Если рядом лежит файл `jython-standalone*.jar`, он будет использован вместо скачивания.

## Лицензия / License

MIT, см. файл `LICENSE`. Jython распространяется на собственных условиях лицензии.
