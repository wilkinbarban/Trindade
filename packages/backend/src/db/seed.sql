-- ============================================================
-- Trindade Massas Operações — Operational Catalog Seed Dataset
-- Reference version 1: categories, tasks, company vehicles, and drivers.
-- User credentials, operational reports, schedules, photos, and audit logs
-- are intentionally excluded to prevent credential and PII disclosure in Git.
-- ============================================================

-- Roles
INSERT OR IGNORE INTO roles (id, name) VALUES (1, 'Administrador');
INSERT OR IGNORE INTO roles (id, name) VALUES (2, 'Trabalhador');

-- Settings
INSERT OR IGNORE INTO settings (key, value) VALUES ('loading_time_slots', '["04:00","04:30","05:00","05:30","06:00","06:30","07:00"]');
INSERT OR IGNORE INTO settings (key, value) VALUES ('reference_dataset_version', '1');

-- Report Categories (6 categories)
INSERT OR IGNORE INTO report_categories (id, parent_category_id, name_pt, name_es, category_type, sort_order, is_active) VALUES (1, NULL, 'Higiene e Organização', 'Higiene y Organización', 'check', 1, 1);
INSERT OR IGNORE INTO report_categories (id, parent_category_id, name_pt, name_es, category_type, sort_order, is_active) VALUES (2, NULL, 'Temperaturas', 'Temperaturas', 'temperature', 2, 1);
INSERT OR IGNORE INTO report_categories (id, parent_category_id, name_pt, name_es, category_type, sort_order, is_active) VALUES (4, NULL, 'Recebimento', 'Recepción de Mercancías', 'check', 4, 1);
INSERT OR IGNORE INTO report_categories (id, parent_category_id, name_pt, name_es, category_type, sort_order, is_active) VALUES (8, NULL, 'Montagem das Caixas', 'Montaje de Cajas', 'check', 3, 1);
INSERT OR IGNORE INTO report_categories (id, parent_category_id, name_pt, name_es, category_type, sort_order, is_active) VALUES (9, 8, 'Caixas pequenas', 'Cajas pequeñas', 'check', 5, 1);
INSERT OR IGNORE INTO report_categories (id, parent_category_id, name_pt, name_es, category_type, sort_order, is_active) VALUES (10, 8, 'Caixas Assai', 'Cajas Assai', 'check', 6, 1);

-- Report Tasks (57 tasks)
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (1, 1, 'Organização do pátio', 'Organización del patio', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (2, 1, 'Organização da área da farinha', 'Organización del área de harina', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (3, 1, 'Organização da Câmara F. Principal', 'Organización de la Cámara F. Principal', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (4, 1, 'Retirar massa dos contêineres', 'Retirar masa de los contenedores', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (5, 2, 'Câmara Principal', 'Cámara Principal', 2, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (10, 4, 'Conferência de notas', 'Verificación de facturas', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (20, 1, 'Organização das caixas plásticas', 'Organización de las cajas plásticas', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (21, 1, 'Separação dos romaneios', 'Separación de los romaneios', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (22, 1, 'Abastecimento da produção', 'Abastecimiento de la producción', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (23, 1, 'Montagem de caixas Produção', 'Montaje de cajas de Producción', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (24, 1, 'Organização do pão de alho', 'Organización del pan de ajo', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (25, 1, 'Liberação de espaço contêineres', 'Liberación de espacio de contenedores', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (26, 1, 'Medir temperatura', 'Medir temperatura', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (27, 1, 'Guardar massa nas câmaras frias', 'Guardar la masa en las cámaras frigoríficas', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (28, 1, 'Organização da saída dos produtos', 'Organización de la salida de los productos', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (29, 1, 'Colocação de plaquinhas', 'Colocación de letreros', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (30, 1, 'Organização das pizzas', 'Organización de las pizzas', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (31, 1, 'Organização dos canudos', 'Organización de las pajitas', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (32, 1, 'Organização do contêiner Pizzas', 'Organización del contenedor de pizzas', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (33, 1, 'Carregamento dos caminhões da casa', 'Carga de los camiones de la casa', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (34, 1, 'Abastecimento de temperos', 'Abastecimiento de condimentos', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (35, 1, 'Organização da área do lixo', 'Organización del área de basura', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (36, 1, 'Organização caixas usadas x itens', 'Organización de cajas usadas por artículos', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (37, 1, 'Organização das batatas', 'Organización de las papas', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (38, 1, 'Organização do barracão', 'Organización del galpón', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (39, 1, 'Organização dos brinquedos', 'Organización de los juguetes', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (40, 2, 'Câmara Nhoque', 'Cámara Nhoque', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (41, 2, 'Câmara Fria 1', 'Cámara Fria 1', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (42, 2, 'Câmara Fria 2', 'Cámara Fria 2', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (43, 4, 'Recebimento de farinha', 'Recepción de harina', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (44, 4, 'Recebimento de canudo', 'Recepción de pajitas', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (45, 4, 'Recebimento de salgadinho', 'Recepción de aperitivos', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (46, 4, 'Recebimento de embalagem', 'Recepción de envases', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (47, 4, 'Recebimento de brinquedo', 'Recepción de juguetes', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (48, 4, 'Recebimento de pizza', 'Recepción de pizzas', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (49, 4, 'Recebimento de batata', 'Recepción de papas', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (50, 4, 'Recebimento de propionato', 'Recepción de propionato', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (51, 4, 'Recebimento de sorbato', 'Recepción de sorbato', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (52, 4, 'Recebimento de queisho', 'Recepción de queso', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (53, 4, 'Recebimento de bobina', 'Recepción de bobinas', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (54, 4, 'Recebimento de sal', 'Recepción de sal', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (55, 4, 'Recebimento de gordura', 'Recepción de grasa', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (56, 4, 'Recebimento de azucar', 'Recepción de azúcar', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (57, 4, 'Recebimento de cajas de carton', 'Recepción de cajas de cartón', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (58, 9, 'Nhoque 400g', 'Ñoquis 400g', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (59, 9, 'Nhoque Kg', 'Ñoquis Kg', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (60, 9, 'Quadrada', 'Cuadrada', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (61, 10, 'Disgo 500g', 'Disgo 500g', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (62, 10, 'Nhoque Kg', 'Ñoquis Kg', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (63, 10, 'Disco 400g', 'Disco 400g', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (64, 10, 'Rolo Kg', 'Rollo Kg', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (65, 10, 'Rolo 2Kg', 'Rollo 2Kg', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (66, 10, 'Quadrada', 'Cuadrada', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (67, 10, 'Rolo 500g', 'Rollo 500g', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (68, 10, 'Lasanha', 'Lasaña', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (69, 4, 'Recebimento de nhoque', 'Recepción de ñoquis', 1, 1, NULL);
INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (70, 4, 'Recebimento de Pão alho ', 'Recibo de pan de ajo', 1, 1, NULL);

-- Vehicles (6 company vehicles)
INSERT OR IGNORE INTO vehicles (id, description, license_plate, is_active) VALUES (2, 'Casa BDG', 'BDG', 1);
INSERT OR IGNORE INTO vehicles (id, description, license_plate, is_active) VALUES (3, 'Casa RKM', 'RKM', 1);
INSERT OR IGNORE INTO vehicles (id, description, license_plate, is_active) VALUES (4, 'Casa RXK', 'RXK', 1);
INSERT OR IGNORE INTO vehicles (id, description, license_plate, is_active) VALUES (5, 'Casa RAA', 'RAA', 1);
INSERT OR IGNORE INTO vehicles (id, description, license_plate, is_active) VALUES (6, 'Casa RPI', 'RPI', 1);
INSERT OR IGNORE INTO vehicles (id, description, license_plate, is_active) VALUES (7, 'Sem matrícula', 'S/M', 1);

-- Drivers / Fleteros (30 active drivers)
INSERT OR IGNORE INTO drivers (id, name, license_plate, driver_type, is_active, created_by_user_id) VALUES (4, 'André', NULL, 'fletero', 1, NULL);
INSERT OR IGNORE INTO drivers (id, name, license_plate, driver_type, is_active, created_by_user_id) VALUES (5, 'Rafael', NULL, 'casa', 1, NULL);
INSERT OR IGNORE INTO drivers (id, name, license_plate, driver_type, is_active, created_by_user_id) VALUES (6, 'Ricardo', NULL, 'casa', 1, NULL);
INSERT OR IGNORE INTO drivers (id, name, license_plate, driver_type, is_active, created_by_user_id) VALUES (7, 'Edinaldo', NULL, 'casa', 1, NULL);
INSERT OR IGNORE INTO drivers (id, name, license_plate, driver_type, is_active, created_by_user_id) VALUES (8, 'Luiz Eloi', NULL, 'fletero', 1, NULL);
INSERT OR IGNORE INTO drivers (id, name, license_plate, driver_type, is_active, created_by_user_id) VALUES (9, 'Matheus', NULL, 'fletero', 1, NULL);
INSERT OR IGNORE INTO drivers (id, name, license_plate, driver_type, is_active, created_by_user_id) VALUES (10, 'Diego', NULL, 'fletero', 1, NULL);
INSERT OR IGNORE INTO drivers (id, name, license_plate, driver_type, is_active, created_by_user_id) VALUES (11, 'Roberto', NULL, 'fletero', 1, NULL);
INSERT OR IGNORE INTO drivers (id, name, license_plate, driver_type, is_active, created_by_user_id) VALUES (12, 'Juverson', NULL, 'casa', 1, NULL);
INSERT OR IGNORE INTO drivers (id, name, license_plate, driver_type, is_active, created_by_user_id) VALUES (13, 'Luis Guilherme', NULL, 'fletero', 1, NULL);
INSERT OR IGNORE INTO drivers (id, name, license_plate, driver_type, is_active, created_by_user_id) VALUES (14, 'José', NULL, 'fletero', 1, NULL);
INSERT OR IGNORE INTO drivers (id, name, license_plate, driver_type, is_active, created_by_user_id) VALUES (15, 'Gustavo', NULL, 'casa', 1, NULL);
INSERT OR IGNORE INTO drivers (id, name, license_plate, driver_type, is_active, created_by_user_id) VALUES (16, 'Ronaldo', NULL, 'fletero', 1, NULL);
INSERT OR IGNORE INTO drivers (id, name, license_plate, driver_type, is_active, created_by_user_id) VALUES (17, 'Everson', NULL, 'fletero', 1, NULL);
INSERT OR IGNORE INTO drivers (id, name, license_plate, driver_type, is_active, created_by_user_id) VALUES (18, 'Carlos Renan', NULL, 'fletero', 1, NULL);
INSERT OR IGNORE INTO drivers (id, name, license_plate, driver_type, is_active, created_by_user_id) VALUES (19, 'Gabriel', NULL, 'fletero', 1, NULL);
INSERT OR IGNORE INTO drivers (id, name, license_plate, driver_type, is_active, created_by_user_id) VALUES (20, 'Luiz', NULL, 'fletero', 1, NULL);
INSERT OR IGNORE INTO drivers (id, name, license_plate, driver_type, is_active, created_by_user_id) VALUES (21, 'Nildo', NULL, 'fletero', 1, NULL);
INSERT OR IGNORE INTO drivers (id, name, license_plate, driver_type, is_active, created_by_user_id) VALUES (22, 'Eduardo', NULL, 'fletero', 1, NULL);
INSERT OR IGNORE INTO drivers (id, name, license_plate, driver_type, is_active, created_by_user_id) VALUES (23, 'Cleberson', NULL, 'fletero', 1, NULL);
INSERT OR IGNORE INTO drivers (id, name, license_plate, driver_type, is_active, created_by_user_id) VALUES (24, 'Adenilson', NULL, 'fletero', 1, NULL);
INSERT OR IGNORE INTO drivers (id, name, license_plate, driver_type, is_active, created_by_user_id) VALUES (25, 'Elton', NULL, 'fletero', 1, NULL);
INSERT OR IGNORE INTO drivers (id, name, license_plate, driver_type, is_active, created_by_user_id) VALUES (26, 'Carlos', NULL, 'fletero', 1, NULL);
INSERT OR IGNORE INTO drivers (id, name, license_plate, driver_type, is_active, created_by_user_id) VALUES (27, 'Admilson', NULL, 'fletero', 1, NULL);
INSERT OR IGNORE INTO drivers (id, name, license_plate, driver_type, is_active, created_by_user_id) VALUES (28, 'Gilson', NULL, 'fletero', 1, NULL);
INSERT OR IGNORE INTO drivers (id, name, license_plate, driver_type, is_active, created_by_user_id) VALUES (29, 'Diego Schultz', NULL, 'fletero', 1, NULL);
INSERT OR IGNORE INTO drivers (id, name, license_plate, driver_type, is_active, created_by_user_id) VALUES (30, 'Fernando', NULL, 'fletero', 1, NULL);
INSERT OR IGNORE INTO drivers (id, name, license_plate, driver_type, is_active, created_by_user_id) VALUES (31, 'Celso', 'RXK', 'fletero', 1, NULL);
INSERT OR IGNORE INTO drivers (id, name, license_plate, driver_type, is_active, created_by_user_id) VALUES (32, 'Antônio', NULL, 'fletero', 1, NULL);
INSERT OR IGNORE INTO drivers (id, name, license_plate, driver_type, is_active, created_by_user_id) VALUES (33, 'Luiz Davi', NULL, 'fletero', 1, NULL);
