-- =============================================================================
--  06. Datos de demostración (opcional)
-- =============================================================================
SET search_path = inventario, public;

INSERT INTO almacen (codigo, nombre, direccion) VALUES
    ('ALM-LIM', 'Almacén Central Lima', 'Av. Argentina 1234, Lima'),
    ('ALM-ARE', 'Almacén Arequipa',     'Av. Ejército 456, Arequipa'),
    ('ALM-TRU', 'Almacén Trujillo',     'Av. España 789, Trujillo');

INSERT INTO categoria (nombre) VALUES ('Abarrotes'), ('Lácteos'), ('Ferretería');

INSERT INTO producto (codigo, nombre, categoria_id, unidad_medida, metodo_valuacion,
                      controla_lote, controla_vencimiento, dias_alerta_vencimiento, precio_venta) VALUES
    ('ARR-001', 'Arroz extra 5 kg',        1, 'BOL', 'PROMEDIO', false, false, 30, 24.90),
    ('ACE-001', 'Aceite vegetal 1 L',      1, 'BOT', 'PEPS',     true,  true,  60,  9.50),
    ('LEC-001', 'Leche evaporada 400 g',   2, 'LAT', 'PROMEDIO', true,  true,  30,  4.20),
    ('YOG-001', 'Yogurt fresa 1 L',        2, 'BOT', 'PEPS',     true,  true,  10,  7.50),
    ('TOR-001', 'Tornillo autorroscante',  3, 'CJA', 'PEPS',     false, false, 30, 18.00);

-- Usuarios de ejemplo (contraseña inicial: demo123).
CALL sp_guardar_usuario(NULL, 'jperez', 'Juan Pérez',  'VENDEDOR',   1, true, 'demo123');
CALL sp_guardar_usuario(NULL, 'mrojas', 'María Rojas', 'ALMACENERO', 1, true, 'demo123');

CALL sp_configurar_stock_minimo(1, 'ARR-001', 40);
CALL sp_configurar_stock_minimo(1, 'LEC-001', 100);
CALL sp_configurar_stock_minimo(2, 'ARR-001', 15);

-- Movimientos con fechas relativas a hoy para que las alertas sean visibles.
CALL sp_registrar_entrada('INVENTARIO_INICIAL', 1, $$[
    {"producto":"ARR-001","cantidad":100,"costo_unitario":18.50},
    {"producto":"TOR-001","cantidad":50, "costo_unitario":12.00}
]$$, 'II-2026', now() - interval '20 days');

CALL sp_registrar_entrada('COMPRA', 1, format($$[
    {"producto":"ACE-001","cantidad":120,"costo_unitario":7.20,"lote":"ACE-A1","fecha_vencimiento":"%s"},
    {"producto":"LEC-001","cantidad":200,"costo_unitario":3.10,"lote":"LEC-0925","fecha_vencimiento":"%s"},
    {"producto":"YOG-001","cantidad":60, "costo_unitario":5.40,"lote":"YOG-77","fecha_vencimiento":"%s"}
]$$, current_date + 300, current_date + 20, current_date + 5)::jsonb,
    'F001-000123', now() - interval '15 days');

CALL sp_registrar_entrada('COMPRA', 1, format($$[
    {"producto":"ARR-001","cantidad":60,"costo_unitario":19.80},
    {"producto":"ACE-001","cantidad":80,"costo_unitario":7.90,"lote":"ACE-B2","fecha_vencimiento":"%s"},
    {"producto":"LEC-001","cantidad":150,"costo_unitario":3.25,"lote":"LEC-1125","fecha_vencimiento":"%s"}
]$$, current_date + 400, current_date + 90)::jsonb,
    'F001-000187', now() - interval '10 days');

CALL sp_registrar_salida('VENTA', 1, $$[
    {"producto":"ARR-001","cantidad":95},
    {"producto":"ACE-001","cantidad":150},
    {"producto":"LEC-001","cantidad":230},
    {"producto":"YOG-001","cantidad":20}
]$$, 'B001-004512', now() - interval '5 days');

CALL sp_transferir(1, 2, $$[
    {"producto":"ARR-001","cantidad":30},
    {"producto":"LEC-001","cantidad":50}
]$$, 'GR-0001', now() - interval '2 days');

CALL sp_generar_alertas_vencimiento();
