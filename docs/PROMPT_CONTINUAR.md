Hola Claude. Continúa un proyecto que empecé en una sesión en la nube. Ahora estás en MI computadora (Windows), así que puedes ejecutar cosas aquí. Háblame en español y explícame paso a paso, no soy experto.

## El proyecto
- Repositorio: https://github.com/leonelchamorrocajahuaman1-glitch/Sistema-de-Gesti-n-Comercial
- Rama con todo el trabajo: `claude/vigilant-sagan-c3euif` (todavía NO está en `main`)
- Es un "Sistema de Gestión Comercial (Stock e Inventario)" con arquitectura cliente-servidor en red local:
  - Computadora 1 (servidor): PostgreSQL con la base única + la aplicación Java.
  - Computadoras cliente: solo la aplicación Java, conectada por JDBC a la IP del servidor.

## Qué ya está hecho
1. Base de datos PostgreSQL 15+ (carpeta `sql/`, se ejecuta en orden 01 → 08):
   - `01_esquema.sql` tablas (almacén, producto, lote, stock, documento, kardex, capa_inventario, consumo_capa, alerta).
   - `02_kardex.sql` trigger del kardex valorizado (Promedio Ponderado y PEPS), stock nunca negativo, kardex inmutable.
   - `03_procedimientos.sql` sp_registrar_entrada, sp_registrar_salida, sp_transferir, sp_inventario_fisico, sp_anular_documento, sp_configurar_stock_minimo.
   - `04_alertas.sql` alertas automáticas de stock mínimo, stock agotado, lote por vencer y lote vencido.
   - `05_vistas.sql` vistas, reporte de kardex y fn_verificar_integridad().
   - `06_usuarios.sql` usuarios con contraseña bcrypt (pgcrypto), fn_login, roles ADMINISTRADOR / ALMACENERO / VENDEDOR.
   - `07_seguridad.sql` rol de red `app_inventario` con permisos mínimos (los movimientos solo pasan por procedimientos).
   - `08_datos_demo.sql` datos de ejemplo opcionales.
2. Aplicación de escritorio Java 17 + Swing + FlatLaf + JDBC en `aplicacion/` (Maven, incluye `mvnw`):
   - Pantallas: Inicio, Usuarios, Productos, Ventas, Compras, Transferencias, Reportes y Almacenes.
   - Clase principal: `pe.sistema.inventario.App`.
   - La conexión se lee de `%USERPROFILE%\.sistema-gestion\conexion.properties` o de `conexion.properties` junto al .jar.
3. Instalador para Windows: `instalacion/windows/INSTALAR.bat` → `instalar_servidor.ps1`.
   - Pide permisos de administrador y solo pregunta la contraseña de `postgres`.
   - Instala Java si falta (winget) y crea la base `inventario`.
   - Genera la contraseña de red y la guarda en conexion.properties.
   - Configura `listen_addresses`, `pg_hba.conf` y el firewall (puerto 5432, solo la subred local).
   - Crea el icono del escritorio y la carpeta `PARA_COMPUTADORAS_CLIENTE`, y abre el programa.
   - Deja un registro en `instalacion.log`.
4. Paquete listo para descargar: `descargas/SistemaGestion-Windows.zip` (jar + sql + instalador).
5. Visual Studio Code (`.vscode/`):
   - Tareas: "1. Instalar base de datos (Windows)", "2. Compilar programa" y "3. Abrir el programa".
   - F5 = "Abrir Sistema de Gestión".
6. Pruebas:
   - `tests/run_tests.sh`: SQL funcionales y de concurrencia.
   - `aplicacion/src/test`: integración JDBC.
   - CI en GitHub Actions; todo pasó en la nube (Linux).

## Lo que NO se pudo probar (en la nube no había Windows)
- `INSTALAR.bat` / `instalar_servidor.ps1` en Windows real.
- La conexión real entre dos computadoras de mi red.

## Lo que necesito que hagas ahora
1. Verifica que estás en mi PC (dime la carpeta actual) y que tengo Java 17+ y PostgreSQL 15+ instalados (`java -version`, y el servicio `postgresql*`). Si falta algo, dime cómo instalarlo.
2. Si no tengo el proyecto, clónalo o descárgalo de la rama `claude/vigilant-sagan-c3euif`.
3. Ejecuta el instalador de la base de datos: la tarea de VS Code "1. Instalar base de datos (Windows)" o `instalacion\windows\INSTALAR.bat` como administrador. YO escribiré la contraseña de `postgres` cuando la pida (no me la pidas por chat).
4. Si falla, lee `instalacion.log` (en la carpeta raíz del proyecto), corrige el script y vuelve a intentar.
5. Compila con `aplicacion\mvnw.cmd -B package -DskipTests` y abre el programa (F5 o `java -jar aplicacion\target\sistema-gestion.jar`).
6. Usuario inicial: `admin` / `admin123` (pide cambiarla). Datos demo: `jperez` y `mrojas` / `demo123`.
7. Si corriges algo, haz commit en la rama `claude/vigilant-sagan-c3euif` y súbelo.

Guía completa de red: `docs/INSTALACION_RED.md`. Documentación general: `README.md`.
