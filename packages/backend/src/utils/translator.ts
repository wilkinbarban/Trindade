const DICTIONARY_ES_TO_PT: Record<string, string> = {
  "higiene y organización": "Higiene e Organização",
  "temperaturas": "Temperaturas",
  "inventario": "Estoque",
  "recepción de mercancías": "Recebimento de Mercadorias",
  "abastecimiento": "Abastecimento",
  "producción": "Produção",
  "lotes": "Lotes",
  "patio organizado": "Pátio organizado",
  "cámaras limpias": "Câmaras limpas",
  "herramientas guardadas": "Ferramentas guardadas",
  "uniforme completo": "Uniforme completo",
  "cámara principal": "Câmara Principal",
  "cámara de enfriamiento": "Câmara de Resfriamento",
  "cajas assaí disponibles": "Caixas Assaí disponíveis",
  "recepción de mercancías (tarea)": "Recebimento de mercadorias",
  "verificación de facturas": "Conferência de notas",
  "abastecimiento realizado": "Abastecimento realizado",
  "nivel de combustible": "Nível de combustível",
  "lotes identificados": "Lotes identificados",
  "vencimiento verificado": "Validade verificada",
  "lotes organizados por fecha": "Lotes organizados por data",
  "montaje de cajas assai": "Montagem de caixas Assai",
  "montaje de cajas pequeñas": "Montagem de caixas Pequenas",
  "todo bien": "Tudo bem",
  "todo en orden": "Tudo em ordem",
  "sin novedad": "Sem novidade",
  "sin novedades": "Sem novidades",
  "se realizó limpieza": "Foi realizada limpeza",
  "se realizo limpieza": "Foi realizada limpeza",
  "área limpia": "Área limpa",
  "area limpia": "Área limpa",
};

const DICTIONARY_PT_TO_ES: Record<string, string> = {};
for (const [es, pt] of Object.entries(DICTIONARY_ES_TO_PT)) {
  DICTIONARY_PT_TO_ES[pt.toLowerCase()] = es;
}

const WORDS_ES_TO_PT: Record<string, string> = {
  "montaje": "montagem",
  "de": "de",
  "cajas": "caixas",
  "pequeñas": "pequenas",
  "grande": "grande",
  "grandes": "grandes",
  "organizado": "organizado",
  "organización": "organização",
  "limpias": "limpas",
  "limpieza": "limpeza",
  "herramientas": "ferramentas",
  "guardadas": "guardadas",
  "completo": "completo",
  "cámara": "câmara",
  "principal": "principal",
  "enfriamiento": "resfriamento",
  "recepción": "recebimento",
  "mercancías": "mercadorias",
  "verificación": "conferência",
  "facturas": "notas",
  "combustible": "combustível",
  "nivel": "nível",
  "vencimiento": "validade",
  "fecha": "data",
  "y": "e",
  "caja": "caixa",
  "assai": "Assai",
  "assaí": "Assaí",
  "producto": "produto",
  "productos": "produtos",
  "tarea": "tarefa",
  "tareas": "tarefas",
  "categoría": "categoria",
  "categorías": "categorias",
  "todo": "tudo",
  "bien": "bem",
  "orden": "ordem",
  "sin": "sem",
  "novedad": "novidade",
  "novedades": "novidades",
  "realizó": "realizou",
  "realizo": "realizou",
  "limpia": "limpa",
  "limpio": "limpo",
  "pendiente": "pendente",
  "observación": "observação",
  "observaciones": "observações",
};

const WORDS_PT_TO_ES: Record<string, string> = {};
for (const [es, pt] of Object.entries(WORDS_ES_TO_PT)) {
  WORDS_PT_TO_ES[pt.toLowerCase()] = es;
}

function wordTranslate(text: string, fromEs: boolean): string {
  const words = text.split(/(\s+)/);
  const dict = fromEs ? WORDS_ES_TO_PT : WORDS_PT_TO_ES;
  return words.map(w => {
    const lower = w.toLowerCase();
    if (dict[lower]) {
      const trans = dict[lower];
      if (w[0] === w[0].toUpperCase() && w.length > 1) {
        return trans[0].toUpperCase() + trans.slice(1);
      }
      return trans;
    }
    return w;
  }).join('');
}

export async function translateText(text: string, fromLang: 'es' | 'pt'): Promise<string> {
  const cleaned = text.trim();
  if (!cleaned) return '';

  const lower = cleaned.toLowerCase();
  if (fromLang === 'es') {
    if (DICTIONARY_ES_TO_PT[lower]) {
      return DICTIONARY_ES_TO_PT[lower];
    }
  } else {
    if (DICTIONARY_PT_TO_ES[lower]) {
      return DICTIONARY_PT_TO_ES[lower];
    }
  }

  const localTranslation = wordTranslate(cleaned, fromLang === 'es').trim();
  return localTranslation || cleaned;
}
