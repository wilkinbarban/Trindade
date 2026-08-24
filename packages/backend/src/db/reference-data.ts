import type { Database } from 'better-sqlite3';

export const REFERENCE_DATASET = {
  name: 'trindade-required-references',
  version: 1,
  roles: [
    { id: 1, name: 'Administrador' },
    { id: 2, name: 'Trabalhador' },
  ],
  categories: [
    [1, null, 'Higiene e Organização', 'Higiene y Organización', 'check', 1, 1], [2, null, 'Temperaturas', 'Temperaturas', 'temperature', 2, 1], [4, null, 'Recebimento de Mercadorias', 'Recepción de Mercancías', 'check', 4, 1], [8, null, 'Montagem das Caixas', 'Montaje de Cajas', 'check', 3, 1], [9, 8, 'Caixas pequenas', 'Cajas pequeñas', 'check', 5, 1], [10, 8, 'Caixas Assai', 'Cajas Assai', 'check', 6, 1],
  ],
  tasks: [
    [1,1,'Organização do pátio','Organización del patio',1,1],[2,1,'Organização da área da farinha','Organización del área de harina',1,1],[3,1,'Organização da Câmara F. Principal','Organización de la Cámara F. Principal',1,1],[4,1,'Retirar massa dos contêineres','Retirar masa de los contenedores',1,1],[5,2,'Câmara Principal','Cámara Principal',2,1],[10,4,'Conferência de notas','Verificación de facturas',1,1],[20,1,'Organização das caixas plásticas','Organización de las cajas plásticas',1,1],[21,1,'Separação dos romaneios','Separación de los romaneios',1,1],[22,1,'Abastecimento da produção','Abastecimiento de la producción',1,1],[23,1,'Montagem de caixas Produção','Montaje de cajas de Producción',1,1],[24,1,'Organização do pão de alho','Organización del pan de ajo',1,1],[25,1,'Liberação de espaço contêineres','Liberación de espacio de contenedores',1,1],[26,1,'Medir temperatura','Medir temperatura',1,1],[27,1,'Guardar massa nas câmaras frias','Guardar la masa en las cámaras frigoríficas',1,1],[28,1,'Organização da saída dos produtos','Organización de la salida de los productos',1,1],[29,1,'Colocação de plaquinhas','Colocación de letreros',1,1],[30,1,'Organização das pizzas','Organización de las pizzas',1,1],[31,1,'Organização dos canudos','Organización de las pajitas',1,1],[32,1,'Organização do contêiner Pizzas','Organización del contenedor de pizzas',1,1],[33,1,'Carregamento dos caminhões da casa','Carga de los camiones de la casa',1,1],[34,1,'Abastecimento de temperos','Abastecimiento de condimentos',1,1],[35,1,'Organização da área do lixo','Organización del área de basura',1,1],[36,1,'Organização caixas usadas x itens','Organización de cajas usadas por artículos',1,1],[37,1,'Organização das batatas','Organización de las papas',1,1],[38,1,'Organização do barracão','Organización del galpón',1,1],[39,1,'Organização dos brinquedos','Organización de los juguetes',1,1],[40,2,'Câmara Nhoque','Cámara Nhoque',1,1],[41,2,'Câmara Fria 1','Cámara Fria 1',1,1],[42,2,'Câmara Fria 2','Cámara Fria 2',1,1],[43,4,'Recebimento de farinha','Recepción de harina',1,1],[44,4,'Recebimento de canudo','Recepción de pajitas',1,1],[45,4,'Recebimento de salgadinho','Recepción de aperitivos',1,1],[46,4,'Recebimento de embalagem','Recepción de envases',1,1],[47,4,'Recebimento de brinquedo','Recepción de juguetes',1,1],[48,4,'Recebimento de pizza','Recepción de pizzas',1,1],[49,4,'Recebimento de batata','Recepción de papas',1,1],[50,4,'Recebimento de propionato','Recepción de propionato',1,1],[51,4,'Recebimento de sorbato','Recepción de sorbato',1,1],[52,4,'Recebimento de queisho','Recepción de queso',1,1],[53,4,'Recebimento de bobina','Recepción de bobinas',1,1],[54,4,'Recebimento de sal','Recepción de sal',1,1],[55,4,'Recebimento de gordura','Recepción de grasa',1,1],[56,4,'Recebimento de azucar','Recepción de azúcar',1,1],[57,4,'Recebimento de cajas de carton','Recepción de cajas de cartón',1,1],[58,9,'Nhoque 400g','Ñoquis 400g',1,1],[59,9,'Nhoque Kg','Ñoquis Kg',1,1],[60,9,'Quadrada','Cuadrada',1,1],[61,10,'Disgo 500g','Disgo 500g',1,1],[62,10,'Nhoque Kg','Ñoquis Kg',1,1],[63,10,'Disco 400g','Disco 400g',1,1],[64,10,'Rolo Kg','Rollo Kg',1,1],[65,10,'Rolo 2Kg','Rollo 2Kg',1,1],[66,10,'Quadrada','Cuadrada',1,1],[67,10,'Rolo 500g','Rollo 500g',1,1],[68,10,'Lasanha','Lasaña',1,1],
  ],
} as const;

export function verifyReferencePrerequisites(db: Database): void {
  const recordedVersion = db.prepare(
    "SELECT value FROM settings WHERE key = 'reference_dataset_version'"
  ).pluck().get();
  if (recordedVersion !== String(REFERENCE_DATASET.version)) {
    throw new Error(`Reference contract drift: version expected ${REFERENCE_DATASET.version}, got ${recordedVersion ?? 'missing'}`);
  }

  const findRole = db.prepare('SELECT name FROM roles WHERE id = ?');
  for (const expected of REFERENCE_DATASET.roles) {
    const role = findRole.get(expected.id) as { name: string } | undefined;
    if (!role) throw new Error(`Reference prerequisite missing: role.id=${expected.id}`);
    if (role.name !== expected.name) {
      throw new Error(`Reference contract drift: role.id=${expected.id}.name expected ${expected.name}, got ${role.name}`);
    }
  }

  verifyRows(db, 'report_categories', ['id','parent_category_id','name_pt','name_es','category_type','sort_order','is_active'], REFERENCE_DATASET.categories);
  verifyRows(db, 'report_tasks', ['id','category_id','name_pt','name_es','temperature_readings','is_active'], REFERENCE_DATASET.tasks);
}

function verifyRows(db: Database, table: string, fields: readonly string[], expectedRows: readonly (readonly unknown[])[]): void {
  const find = db.prepare(`SELECT ${fields.join(',')} FROM ${table} WHERE id = ?`);
  for (const expected of expectedRows) {
    const actual = find.get(expected[0]) as Record<string, unknown> | undefined;
    if (!actual) throw new Error(`Reference prerequisite missing: ${table}.id=${expected[0]}`);
    fields.forEach((field, index) => {
      if (actual[field] !== expected[index]) throw new Error(`Reference contract drift: ${table}.id=${expected[0]}.${field} expected ${expected[index]}, got ${actual[field]}`);
    });
  }
}
