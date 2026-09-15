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

const TRANSLATION_TIMEOUT_MS = 2000;

async function fetchWithTimeout(url: string, init?: RequestInit): Promise<Response> {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), TRANSLATION_TIMEOUT_MS);
  try {
    return await fetch(url, { ...init, signal: controller.signal });
  } finally {
    clearTimeout(timeoutId);
  }
}

async function translateWithGoogle(cleaned: string, fromLang: 'es' | 'pt'): Promise<string | null> {
  const apiKey = process.env.GOOGLE_TRANSLATE_API_KEY?.trim();
  if (!apiKey) return null;

  const targetLang = fromLang === 'es' ? 'pt-BR' : 'es';
  const sourceLang = fromLang === 'es' ? 'es' : 'pt';
  const url = `https://translation.googleapis.com/language/translate/v2?key=${encodeURIComponent(apiKey)}`;

  const res = await fetchWithTimeout(url, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      q: cleaned,
      source: sourceLang,
      target: targetLang,
      format: 'text',
    }),
  });

  if (!res.ok) return null;
  const data = await res.json() as any;
  const translated = data?.data?.translations?.[0]?.translatedText;
  return typeof translated === 'string' && translated.trim() ? translated.trim() : null;
}

async function translateWithMyMemory(cleaned: string, fromLang: 'es' | 'pt'): Promise<string | null> {
  const langpair = fromLang === 'es' ? 'es|pt' : 'pt|es';
  const url = `https://api.mymemory.translated.net/get?q=${encodeURIComponent(cleaned)}&langpair=${langpair}`;
  const res = await fetchWithTimeout(url);
  if (!res.ok) return null;

  const data = await res.json() as any;
  const translated = data?.responseData?.translatedText;
  if (translated && typeof translated === 'string' && !translated.startsWith('MYMEMORY WARNING')) {
    return translated.trim();
  }
  return null;
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

  try {
    const googleTranslation = await translateWithGoogle(cleaned, fromLang);
    if (googleTranslation) return googleTranslation;
  } catch {
    // Translation must never block admin saves. Continue to the public/local fallbacks.
  }

  try {
    const myMemoryTranslation = await translateWithMyMemory(cleaned, fromLang);
    if (myMemoryTranslation) return myMemoryTranslation;
  } catch {
    // Fail silently to local translator.
  }

  const localTranslation = wordTranslate(cleaned, fromLang === 'es').trim();
  return localTranslation || cleaned;
}
