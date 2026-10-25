-- =============================================================================
--  Sistema de Gestión Comercial — Stock e Inventario
--  01. Esquema relacional
--  Motor: PostgreSQL 15+  (usa UNIQUE NULLS NOT DISTINCT y procedimientos)
-- =============================================================================
DROP SCHEMA IF EXISTS inventario CASCADE;
CREATE SCHEMA inventario;
SET search_path = inventario, public;

-- -----------------------------------------------------------------------------
--  Tipos de dominio
-- -----------------------------------------------------------------------------
CREATE TYPE metodo_valuacion AS ENUM ('PROMEDIO', 'PEPS');

CREATE TYPE tipo_movimiento AS ENUM ('ENTRADA', 'SALIDA');

CREATE TYPE tipo_documento AS ENUM (
    -- entradas
    'INVENTARIO_INICIAL', 'COMPRA', 'DEVOLUCION_CLIENTE', 'AJUSTE_POSITIVO',
    -- salidas
    'VENTA', 'CONSUMO', 'MERMA', 'DEVOLUCION_PROVEEDOR', 'AJUSTE_NEGATIVO',
    -- mixtos
    'TRANSFERENCIA', 'INVENTARIO_FISICO', 'ANULACION'
);

CREATE TYPE estado_documento AS ENUM ('PROCESADO', 'ANULADO');

CREATE TYPE tipo_alerta AS ENUM (
    'STOCK_MINIMO', 'STOCK_AGOTADO', 'LOTE_POR_VENCER', 'LOTE_VENCIDO'
);

-- -----------------------------------------------------------------------------
--  Maestros
-- -----------------------------------------------------------------------------
CREATE TABLE almacen (
    id          integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo      varchar(10)  NOT NULL UNIQUE,
    nombre      varchar(100) NOT NULL,
    direccion   varchar(200),
    activo      boolean      NOT NULL DEFAULT true
);

CREATE TABLE categoria (
    id      integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nombre  varchar(80) NOT NULL UNIQUE
);

CREATE TABLE producto (
    id                       integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo                   varchar(20)  NOT NULL UNIQUE,
    nombre                   varchar(150) NOT NULL,
    categoria_id             integer REFERENCES categoria (id),
    unidad_medida            varchar(10)  NOT NULL DEFAULT 'UND',
    metodo_valuacion         metodo_valuacion NOT NULL DEFAULT 'PROMEDIO',
    controla_lote            boolean NOT NULL DEFAULT false,
    controla_vencimiento     boolean NOT NULL DEFAULT false,
    dias_alerta_vencimiento  integer NOT NULL DEFAULT 30 CHECK (dias_alerta_vencimiento >= 0),
    activo                   boolean NOT NULL DEFAULT true,
    CONSTRAINT ck_producto_vencimiento_requiere_lote
        CHECK (NOT controla_vencimiento OR controla_lote)
);

CREATE TABLE lote (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    producto_id        integer NOT NULL REFERENCES producto (id),
    codigo             varchar(40) NOT NULL,
    fecha_fabricacion  date,
    fecha_vencimiento  date,
    CONSTRAINT uq_lote_producto_codigo UNIQUE (producto_id, codigo),
    CONSTRAINT ck_lote_fechas
        CHECK (fecha_fabricacion IS NULL OR fecha_vencimiento IS NULL
               OR fecha_vencimiento >= fecha_fabricacion)
);
-- Necesaria para la FK compuesta (lote debe pertenecer al producto).
ALTER TABLE lote ADD CONSTRAINT uq_lote_id_producto UNIQUE (id, producto_id);

-- -----------------------------------------------------------------------------
--  Saldos (solo modificables a través del kardex)
-- -----------------------------------------------------------------------------
CREATE TABLE stock (
    almacen_id      integer NOT NULL REFERENCES almacen (id),
    producto_id     integer NOT NULL REFERENCES producto (id),
    cantidad        numeric(14,4) NOT NULL DEFAULT 0,
    valor_total     numeric(18,4) NOT NULL DEFAULT 0,
    costo_promedio  numeric(18,6) NOT NULL DEFAULT 0,
    stock_minimo    numeric(14,4) NOT NULL DEFAULT 0,
    actualizado_en  timestamptz   NOT NULL DEFAULT now(),
    PRIMARY KEY (almacen_id, producto_id),
    -- Última línea de defensa contra stocks negativos.
    CONSTRAINT ck_stock_no_negativo  CHECK (cantidad >= 0),
    CONSTRAINT ck_valor_no_negativo  CHECK (valor_total >= 0),
    CONSTRAINT ck_costo_no_negativo  CHECK (costo_promedio >= 0),
    CONSTRAINT ck_minimo_no_negativo CHECK (stock_minimo >= 0),
    CONSTRAINT ck_stock_cero_sin_valor CHECK (cantidad > 0 OR valor_total = 0)
);

-- -----------------------------------------------------------------------------
--  Documentos y kardex
-- -----------------------------------------------------------------------------
CREATE TABLE documento (
    id                    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tipo                  tipo_documento   NOT NULL,
    fecha                 timestamptz      NOT NULL DEFAULT now(),
    almacen_id            integer          NOT NULL REFERENCES almacen (id),
    almacen_destino_id    integer          REFERENCES almacen (id),
    referencia            varchar(60),
    observacion           text,
    estado                estado_documento NOT NULL DEFAULT 'PROCESADO',
    documento_anulado_id  bigint UNIQUE REFERENCES documento (id),
    usuario               name             NOT NULL DEFAULT current_user,
    creado_en             timestamptz      NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_doc_transferencia CHECK (
        (tipo = 'TRANSFERENCIA') = (almacen_destino_id IS NOT NULL)
        AND almacen_destino_id IS DISTINCT FROM almacen_id),
    CONSTRAINT ck_doc_anulacion CHECK (
        (tipo = 'ANULACION') = (documento_anulado_id IS NOT NULL))
);

-- Libro mayor de inventario: una fila por movimiento físico en un almacén.
-- Las columnas de costo y saldo las calcula exclusivamente el trigger
-- trg_kardex_procesar; nunca se actualizan ni eliminan (ver 02_kardex.sql).
CREATE TABLE kardex (
    id                    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    documento_id          bigint          NOT NULL REFERENCES documento (id),
    fecha                 timestamptz     NOT NULL,
    almacen_id            integer         NOT NULL REFERENCES almacen (id),
    producto_id           integer         NOT NULL REFERENCES producto (id),
    lote_id               bigint,
    tipo_movimiento       tipo_movimiento NOT NULL,
    -- ENTRADA: capa creada; SALIDA: capa específica a consumir (opcional).
    capa_id               bigint,
    cantidad              numeric(14,4)   NOT NULL CHECK (cantidad > 0),
    costo_unitario        numeric(18,6)   NOT NULL CHECK (costo_unitario >= 0),
    costo_total           numeric(18,4)   NOT NULL CHECK (costo_total >= 0),
    saldo_cantidad        numeric(14,4)   NOT NULL CHECK (saldo_cantidad >= 0),
    saldo_costo_unitario  numeric(18,6)   NOT NULL CHECK (saldo_costo_unitario >= 0),
    saldo_valor           numeric(18,4)   NOT NULL CHECK (saldo_valor >= 0),
    permitir_vencido      boolean         NOT NULL DEFAULT false,
    kardex_origen_id      bigint REFERENCES kardex (id),
    creado_en             timestamptz     NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT fk_kardex_lote_producto FOREIGN KEY (lote_id, producto_id)
        REFERENCES lote (id, producto_id)
);
CREATE INDEX ix_kardex_almacen_producto ON kardex (almacen_id, producto_id, fecha DESC, id DESC);
CREATE INDEX ix_kardex_documento ON kardex (documento_id);
CREATE INDEX ix_kardex_origen ON kardex (kardex_origen_id) WHERE kardex_origen_id IS NOT NULL;

-- Capas de costo / existencias por lote.  Cada ENTRADA genera una capa;
-- las SALIDAS las consumen en orden PEPS (FIFO) o FEFO según el producto.
CREATE TABLE capa_inventario (
    id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    almacen_id           integer       NOT NULL REFERENCES almacen (id),
    producto_id          integer       NOT NULL REFERENCES producto (id),
    lote_id              bigint,
    kardex_id            bigint        NOT NULL UNIQUE
                         REFERENCES kardex (id) DEFERRABLE INITIALLY DEFERRED,
    fecha_ingreso        timestamptz   NOT NULL,
    cantidad_inicial     numeric(14,4) NOT NULL CHECK (cantidad_inicial > 0),
    cantidad_disponible  numeric(14,4) NOT NULL,
    costo_unitario       numeric(18,6) NOT NULL CHECK (costo_unitario >= 0),
    CONSTRAINT ck_capa_disponible
        CHECK (cantidad_disponible >= 0 AND cantidad_disponible <= cantidad_inicial),
    CONSTRAINT fk_capa_lote_producto FOREIGN KEY (lote_id, producto_id)
        REFERENCES lote (id, producto_id)
);
CREATE INDEX ix_capa_disponible ON capa_inventario (almacen_id, producto_id, fecha_ingreso, id)
    WHERE cantidad_disponible > 0;
CREATE INDEX ix_capa_lote ON capa_inventario (lote_id) WHERE lote_id IS NOT NULL;

ALTER TABLE kardex ADD CONSTRAINT fk_kardex_capa
    FOREIGN KEY (capa_id) REFERENCES capa_inventario (id) DEFERRABLE INITIALLY DEFERRED;

-- Trazabilidad de qué capas (lotes y costos) consumió cada salida.
CREATE TABLE consumo_capa (
    kardex_id       bigint        NOT NULL REFERENCES kardex (id) DEFERRABLE INITIALLY DEFERRED,
    capa_id         bigint        NOT NULL REFERENCES capa_inventario (id),
    cantidad        numeric(14,4) NOT NULL CHECK (cantidad > 0),
    costo_unitario  numeric(18,6) NOT NULL CHECK (costo_unitario >= 0),
    PRIMARY KEY (kardex_id, capa_id)
);
CREATE INDEX ix_consumo_capa_capa ON consumo_capa (capa_id);

-- -----------------------------------------------------------------------------
--  Alertas
-- -----------------------------------------------------------------------------
CREATE TABLE alerta (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tipo         tipo_alerta NOT NULL,
    almacen_id   integer     NOT NULL REFERENCES almacen (id),
    producto_id  integer     NOT NULL REFERENCES producto (id),
    lote_id      bigint      REFERENCES lote (id),
    mensaje      text        NOT NULL,
    creada_en    timestamptz NOT NULL DEFAULT clock_timestamp(),
    resuelta     boolean     NOT NULL DEFAULT false,
    resuelta_en  timestamptz
);
-- Como máximo una alerta abierta por (tipo, almacén, producto, lote).
CREATE UNIQUE INDEX uq_alerta_abierta
    ON alerta (tipo, almacen_id, producto_id, lote_id) NULLS NOT DISTINCT
    WHERE NOT resuelta;
