import Database from 'better-sqlite3';

const db = new Database('/app/packages/backend/data/trindade.db');
const tasks = [
  ['Organização do pátio', 'Organización del patio'],
  ['Organização da área da farinha', 'Organización del área de harina'],
  ['Organização da Câmara F. Principal', 'Organización de la Cámara F. Principal'],
  ['Retirar massa dos contêineres', 'Retirar masa de los contenedores'],
  ['Organização das caixas plásticas', 'Organización de las cajas plásticas'],
  ['Separação dos romaneios', 'Separación de los romaneios'],
  ['Abastecimento da produção', 'Abastecimiento de la producción'],
  ['Montagem de caixas Produção', 'Montaje de cajas de Producción'],
  ['Organização do pão de alho', 'Organización del pan de ajo'],
  ['Liberação de espaço contêineres', 'Liberación de espacio de contenedores'],
  ['Medir temperatura', 'Medir temperatura'],
  ['Guardar massa nas câmaras frias', 'Guardar la masa en las cámaras frigoríficas'],
  ['Organização da saída dos produtos', 'Organización de la salida de los productos'],
  ['Colocação de plaquinhas', 'Colocación de letreros'],
  ['Organização das pizzas', 'Organización de las pizzas'],
  ['Organização dos canudos', 'Organización de las pajitas'],
  ['Organização do contêiner Pizzas', 'Organización del contenedor de pizzas'],
];

const update = db.prepare(
  'UPDATE report_tasks SET category_id = 1, name_pt = ?, name_es = ?, is_active = 1 WHERE id = ?',
);
const insert = db.prepare(
  `INSERT INTO report_tasks
   (category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id)
   SELECT 1, ?, ?, 1, 1, 1
   WHERE NOT EXISTS (SELECT 1 FROM report_tasks WHERE category_id = 1 AND name_pt = ?)`,
);

db.transaction(() => {
  for (let i = 0; i < 4; i += 1) update.run(tasks[i][0], tasks[i][1], i + 1);
  for (let i = 4; i < tasks.length; i += 1) insert.run(tasks[i][0], tasks[i][1], tasks[i][0]);
})();

console.log(JSON.stringify(
  db.prepare('SELECT id, name_pt, name_es, is_active FROM report_tasks WHERE category_id = 1 ORDER BY id').all(),
  null,
  2,
));
db.close();
