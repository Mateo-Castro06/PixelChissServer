# Comprender Auth Service

Esta guía describe el código de este microservicio y sus contratos actuales.
Las rutas del Gateway se revisaron en modo lectura; el cliente Godot no está en
este repositorio. La compatibilidad aquí se caracteriza por los contratos del
servidor, sin sustituir una prueba real con Godot y Google.

- [Arquitectura y responsabilidades](architecture.md)
- [Flujos, seguridad y contratos](authentication.md)
- [Decisiones y riesgos de la refactorización](refactoring.md)
- [Pruebas y resultados](verification.md)

Para estudiar el código, comienza por
[`AuthController`](../src/main/kotlin/com/pixelchiss/auth/controller/AuthController.kt),
sigue sus servicios y luego revisa
[`SecurityConfig`](../src/main/kotlin/com/pixelchiss/auth/security/SecurityConfig.kt)
y [`OAuth2LoginSuccessHandler`](../src/main/kotlin/com/pixelchiss/auth/security/OAuth2LoginSuccessHandler.kt).
