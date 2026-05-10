# APS Cloud — фронтенд

React + Vite (JavaScript) — клиент к серверу `APS_Server` (Spring WebFlux REST).

## Запуск

```bash
cd frontend
npm install
npm run dev
```

Откроется на http://localhost:5173. Vite проксирует `/auth/**`, `/api/**`, `/admin/**`
на `http://localhost:8080` (бэкенд) — поэтому Spring сервер должен быть запущен.

## Возможности UI

- Авторизация и регистрация (как пользователь и как администратор)
- Файловый браузер с переходом по папкам и хлебными крошками
- Загрузка файлов (кнопка + drag-and-drop с прогрессом)
- Создание папок
- Скачивание (через JWT)
- Корзина: soft-delete и purge
- Контекстное меню (правый клик) на файлах и папках
- Два режима отображения — плитка и список
- Поиск, тосты, скелетон-лоадер

## Известные заглушки сервера (помечены в UI)

Эндпоинты `rename`, `move`, `copy`, `restore` — заглушки на сервере (в процессе исправления).
В UI они показаны с тегом `STUB`, чтобы было понятно, что действие может не повлиять
на состояние сервера.

## Структура

```
src/
  api/client.js          ← fetch-обёртка ко всем эндпоинтам
  context/
    AuthContext.jsx      ← JWT, login/register/logout
    ToastContext.jsx     ← уведомления
  components/
    Layout.jsx           ← сайдбар + main
    Icon.jsx             ← inline SVG иконки
    FileIcon.jsx         ← иконка по MIME-типу
    Modal.jsx
    Breadcrumbs.jsx
  pages/
    Login.jsx
    Register.jsx
    Dashboard.jsx        ← основной браузер
  styles.css
```
