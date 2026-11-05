# Flow Music Player Multiplataforma: Normalización ReplayGain/LUFS, Multi-Etiquetas, Códecs Extendidos, Gapless y Optimización Extrema

Evolución integral de **Flow Music Player** (fork multiplataforma GPLv3 de `https://github.com/MrDemonc/Lune`) para Android, Windows y Linux que incorpora normalización de sonoridad ReplayGain/R128/LUFS en `commonMain` con análisis acústico en tiempo real, indexación inteligente de múltiples artistas y géneros sin conflictos, decodificación universal de formatos modernos y clásicos con pre-buffering **Gapless** real en ExoPlayer/Media3 y PC, widget de Android blindado y una interfaz camaleónica de ultra bajo consumo capaz de volar incluso en PCs modestas o móviles antiguos.

## Revisión del Usuario y Decisiones Confirmadas

> [!IMPORTANT]
> Las siguientes decisiones arquitectónicas reflejan tus respuestas confirmadas y rigen toda la implementación de **Flow Music Player** en Android, Windows y Linux.

- **Normalización ReplayGain/LUFS Híbrida (`commonMain`)**: Servicio compartido `ReplayGainTagService` con `jaudiotagger` para lectura prioritaria de etiquetas estándar (`ReplayGain 1/2`, `EBU R128`, `iTunNORM` y etiquetas `LUFS` directas), respaldada por análisis BS.1770-4 K-weighted LUFS en tiempo real y muestreo disperso para pistas sin etiquetar, con preamplificación ajustable y limitador de picos anticorte (*clipping protection*).
- **Motor Multi-Artista y Multi-Género Sin Conflictos**: División configurable por punto y coma (`;`), coma (`,`), barra (`/`), barra invertida, pleca (`|`), ampersand (`&`) y conectores de colaboración (`ft.`, `feat.`, `featuring`, `con`, `with`, `vs.`, `presents`, ` x `), protegido por una **Lista Blanca editable por el usuario** (ej. *AC/DC*, *Tyler, The Creator*, *Earth, Wind & Fire*, *Guns N' Roses*, *R&B*) y **extracción inteligente de colaboradores desde el título** (ej. `(ft. Daddy Yankee)`) con deduplicación automática para evitar colisiones o artistas duplicados.
- **Decodificación Extendida + Gapless con Pre-Buffering Libre de AVX**: Motor híbrido basado en `Media3 ExoPlayer` con pre-carga anticipada de la siguiente pista de la cola (`queueNextGapless`) + decodificador nativo puro en Android y tubería PCM universal `FFmpeg` compatible con CPU base SSE2 (más respaldo puro del sistema) en Windows y Linux, habilitando `ALAC`, `AIFF`, `APE`, `WavPack`, `DSD`, contenedores `M4A/MP4/MKV/MKA/WEBM`, `Opus` estéreo y multicanal (con mezcla descendente limpia a estéreo), `EAC3`, `AC3` y `AC4`, junto con transición **Gapless** exacta al nivel de muestra.
- **Perfiles Escalonados para Hardware Ultra Modesto**: Detección automática de capacidades de CPU, RAM, disco mecánico y GPU (con selector manual en Ajustes entre *Ultra-Ahorro*, *Modo Ligero* y *Completo*) diseñada pensando no solo en un Pentium E5800 + GT 610 (`1280x1024`), sino en máquinas aún más modestas (procesadores antiguos sin SSE4/AVX, 1–2 GB de RAM, discos duros HDD y gráficos integrados o renderizado por software).

---

## 1. Visión General y Concepto Principal

- **Qué Hace**: Convierte a **Flow Music Player** en un reproductor audiófilo verdaderamente universal que mantiene un volumen uniforme entre pistas de distintas épocas y masterizaciones, organiza bibliotecas complejas con colaboraciones múltiples sin fragmentar bandas reales, reproduce prácticamente cualquier formato o contenedor de audio sin silencios entre pistas continuas y adapta tanto su consumo de recursos como su geometría visual a cualquier dispositivo.
- **Público y Escenarios Objetivo**: Usuarios exigentes con bibliotecas locales heterogéneas (desde grabaciones clásicas y álbumes en vivo sin pausas hasta música urbana con múltiples colaboradores y contenedores descargados o ripeados) que alternan entre teléfonos Android (API 24+) y computadoras con Linux (MX Linux / Xfce / PipeWire / ALSA) o Windows, incluyendo hardware veterano de hace más de 15 años.
- **Valor Principal**: Cero sacrificios funcionales en hardware humilde: todas las capacidades audiófilas (ReplayGain, Gapless, Crossfade armónico, ecualización, multi-artistas y todos los formatos) permanecen 100 % operativas reduciendo al mínimo el uso de CPU en reposo, memoria RAM, lecturas aleatorias en discos HDD y carga de sombreadores en GPU.

---

## 2. Experiencia de Usuario y Diseño Visual Adaptativo

### Flujos Principales de Usuario

1. **Reproducción Continua con ReplayGain y Gapless Inteligente**:
   - Al reproducir una lista variada, Flow iguala la sonoridad percibida al objetivo de referencia sin aplastar el rango dinámico ni saturar picos.
   - Si una pista carece de metadatos ReplayGain, el analizador ligero calcula su energía LUFS y guarda el valor en caché local para no volver a leer el disco.
   - Al escuchar un álbum conceptual o en directo en orden secuencial, Flow pre-carga la siguiente pista en la cola de ExoPlayer y activa automáticamente la transición **Gapless** exacta entre pistas consecutivas (omitiendo el fundido cruzado si la opción *Respetar Gapless en álbumes* está activa).
2. **Navegación Multi-Artista y Multi-Género**:
   - Una canción etiquetada como `"Daft Punk; Pharrell Williams, Nile Rodgers"` o con título `"Get Lucky (ft. Pharrell Williams)"` indexa automáticamente la pista dentro del catálogo individual de cada artista participante sin duplicar nombres ni partir grupos protegidos en la Lista Blanca.
   - En el Reproductor Completo y en la hoja de detalles de pista, los artistas y géneros aparecen como **píldoras interactivas**: tocar un artista abre su discografía directa (o un selector instantáneo de colaboradores si se pulsa el subtítulo general).
   - Desde Ajustes o el propio Editor de Metadatos, el usuario puede añadir o quitar nombres en la **Lista Blanca de Excepciones**, activar o desactivar separadores individuales y elegir si desea limpiar visualmente los `(feat. ...)` del título mostrado.
3. **Adaptación Universal de Pantalla (5:4, 4:3, Móvil Compacto, Plegable y Ultrawide)**:
   - **Monitores 5:4 y 4:3 (`1280x1024`, `1024x768`) y Ventanas Redimensionadas**: Diseño de escritorio con riel lateral compacto, paneles divisibles proporcionales y barra de transporte inferior densa donde ni las portadas ni los controles quedan cortados verticalmente.
   - **Móviles Pequeños (`320dp`) y Modo Horizontal (`<420dp` de alto)**: En vertical usa jerarquía fluida sin desbordes; al girar el teléfono en horizontal o dividir pantalla, el Reproductor Completo pasa automáticamente a un diseño de dos columnas (portada a la izquierda, controles y letras/cola a la derecha).
   - **Monitores Panorámicos y Tablets**: Diseño canónico *Lista + Panel de Detalle / Cola Activa* con columnas de cuadrícula adaptativas.
4. **Centro Ampliado de Configuración y Personalización**:
   - Nuevas secciones claras tanto en Android como en PC: **Normalización y ReplayGain**, **Reproducción Gapless y Formatos**, **Reglas Multi-Artista / Lista Blanca**, **Rendimiento y Hardware Modesto** (con indicador del perfil activo y selector de salida de audio ALSA/PipeWire/USB en PC) y **Apariencia y Densidad Visual** (densidad compacta/estándar/amplia, escala tipográfica, número de columnas, tema negro puro de alto contraste e insignias técnicas personalizables en el reproductor).

### Identidad Visual, Tema y Movimiento

- **Dirección Estética**: *Estudio Audiófilo de Precisión*: superficies oscuras o claras de alto contraste legibles en monitores LCD antiguos (TN/5:4) y paneles OLED modernos, acentos cromáticos nítidos derivados de la carátula y tipografía clara con jerarquía técnica para frecuencias de muestreo, códec y ganancia ReplayGain (`-3.4 dB RG`).
- **Paleta y Tokens Visuales**: Uso semántico estricto del esquema Material 3 (`background`, `surfaceContainerLow`, `primary`, `secondaryContainer`, `onSurfaceVariant`), añadiendo un modo **Negro Puro / Alto Contraste** libre de transparencias costosas ideal para entornos ligeros como Xfce y pantallas antiguas.
- **Movimiento Consciente del Hardware**:
  - En perfil *Completo*: transiciones elásticas suaves, desenfoque acelerado por hardware y espectro fluido.
  - En *Modo Ligero* y *Ultra-Ahorro*: degradados tonales precalculados de costo cero en GPU, desactivación automática de animaciones infinitas en segundo plano (consumo cercano a **0 % de CPU/GPU** cuando la ventana está quieta) y actualización de barra de progreso optimizada a baja frecuencia para no despertar innecesariamente los núcleos del procesador.

---

## 3. Decisiones Clave de Producto y Compensaciones Técnicas

- **Decisión 1: Análisis Ligero por Ventanas Dispersas frente a Escaneo EBU R128 Completo de Archivo**
  - *Enfoque Elegido*: Priorizar etiquetas `ReplayGain` / `R128` / `iTunNORM` embebidas y, cuando no existan, muestrear únicamente breves segmentos distribuidos de la pista (ej. 25 %, 50 % y 75 % de la duración) para estimar sonoridad RMS ponderada y pico máximo, persistiendo el resultado en la caché local.
  - *Por Qué*: Escanear archivos completos en discos mecánicos HDD y CPUs de 1 o 2 núcleos saturaría la lectura de disco y la CPU durante minutos. El muestreo por ventanas tarda apenas milisegundos, protege los discos duros y ofrece una nivelación perceptual consistente.
- **Decisión 2: Tubería Externa SSE2 + Decodificadores del Sistema frente a Bibliotecas Nativas Pesadas con AVX**
  - *Enfoque Elegido*: En PC (Linux/Windows), conectar un puente directo sin archivos temporales hacia `ffmpeg` del sistema (cuando esté disponible) con conversión directa a PCM estéreo de 16/24 bits sobre un búfer circular de 64 KB, manteniendo respaldo automático mediante JavaSound y lectores nativos WAV/AIFF; en Android, combinar `Media3 ExoPlayer` con extracción nativa multicanal.
  - *Por Qué*: Muchos empaquetados modernos de códecs exigen instrucciones `AVX` o `SSE4.2` que provocan cierres inmediatos (`SIGILL`) en procesadores como el Intel Pentium E5800 o anteriores. Usar el binario base del sistema operativo en Linux/Windows aprovecha paquetes compilados para `x86_64` base (SSE2), soporta `ALAC`, `AIFF`, `MKV`, `MP4`, `Opus 5.1/7.1`, `EAC3` y `AC4` y mantiene el ejecutable de Lune diminuto.
- **Decisión 3: Recolector de Basura y Renderizado Frugal en Escritorio**
  - *Enfoque Elegido*: Reemplazar recolectores concurrentes pesados pensados para servidores multinúcleo por una configuración JVM frugal (`G1GC` compacto / `SerialGC` opcional para equipos de <=2 GB RAM, techo de memoria contenido y caída automática de OpenGL a renderizado seguro si el controlador propietario antiguo presenta limitaciones).
  - *Por Qué*: En procesadores de 1 o 2 hilos como el Pentium E5800 y tarjetas como la GeForce GT 610 (driver serie 390), reducir hilos de fondo y picos de sombreadores garantiza que la música jamás sufra microcortes (*underruns*) aunque el navegador u otros programas estén abiertos.
- **Decisión 4: Escudo de Lista Blanca Antes de Extraer y Dividir Artistas**
  - *Enfoque Elegido*: Ejecutar un análisis determinista en tres etapas: (1) enmascarar coincidencias de la Lista Blanca, (2) extraer colaboradores explícitos del título (`ft.`, `feat.`, `con`, `with`) ignorando etiquetas de versión como `(Remix)` o `(Live)`, y (3) dividir separadores y unificar sin duplicados ignorando mayúsculas/minúsculas.
  - *Por Qué*: Evita por completo que una coma rompa *"Tyler, The Creator"*, que una barra rompa *"AC/DC"* o que un artista presente tanto en el título como en el campo Artista aparezca duplicado.

---

## 4. Arquitectura Técnica y Estrategia de Datos

### Diagrama de Arquitectura Multiplataforma

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                     NÚCLEO COMPARTIDO MULTIPLATAFORMA                        │
│                                                                              │
│  ┌─────────────────────────┐  ┌──────────────────────────┐                   │
│  │   Motor ReplayGain      │  │ Analizador Multi-Tag     │                   │
│  │ • RG1/RG2, R128, iTunes │  │ • Escudo Lista Blanca    │                   │
│  │ • Estimador ligero RMS  │  │ • Separadores (; , / ft) │                   │
│  │ • Preamplif + Limitador │  │ • Extractor de Títulos   │                   │
│  └────────────┬────────────┘  └────────────┬─────────────┘                   │
│               │                            │                                 │
│  ┌────────────▼────────────────────────────▼─────────────┐                   │
│  │     Registro Universal de Formatos + Planificador     │                   │
│  │  • ALAC, AIFF, MKV, MP4, Opus 5.1, EAC3, AC4, WV, APE │                   │
│  │  • Decisor Gapless vs Crossfade + Perfiles Hardware   │                   │
│  └────────────┬──────────────────────────────────────────┴───┐               │
└───────────────┼──────────────────────────────────────────────┼───────────────┘
                │                                              │
      ┌─────────▼─────────┐                          ┌─────────▼─────────┐
      │   MOTOR ANDROID   │                          │  MOTOR PC (WIN/LX)│
      │ • Media3 + Nativo │                          │ • Puente FFmpeg   │
      │ • Downmix Estéreo │                          │   SSE2 + JavaSound│
      │ • Cadena Gapless  │                          │ • Salida ALSA /   │
      │ • DSP + ReplayGain│                          │   PipeWire / USB  │
      └─────────┬─────────┘                          │ • Búfer Continuo  │
                │                                    │   Gapless 64 KB   │
                │                                    └─────────┬─────────┘
      ┌─────────▼──────────────────────────────────────────────▼─────────┐
      │         CAPA DE INTERFAZ CAMALEÓNICA Y MODO ULTRA-LIGERO         │
      │  • Geometría Dinámica: Móvil 320dp | Horizontal | 5:4 | 16:9+    │
      │  • Píldoras Multi-Artista / Multi-Género + Editor Lista Blanca   │
      │  • Perfiles Visuales: Ultra-Ahorro (0% reposo) | Ligero | Total  │
      └──────────────────────────────────────────────────────────────────┘
```

### Modelo de Datos y Estado

- **Entidad de Pista Enriquecida**: Conserva compatibilidad total con la caché actual e incorpora listas normalizadas `artists: List<String>`, `genres: List<String>`, `displayTitle: String` (con o sin sufijo `feat.` según preferencia), metadatos de sonoridad (`replayGainTrackDb`, `replayGainAlbumDb`, `replayGainTrackPeak`, `replayGainAlbumPeak`, `computedLoudnessDb`) e indicadores de contenedor/canales (`codecName`, `channelCount`).
- **Configuración de Normalización (`ReplayGainConfig`)**: Modo (`OFF`, `TRACK`, `ALBUM`, `SMART_ALBUM`), ganancia previa para pistas etiquetadas (`-12` a `+12 dB`), ganancia previa para pistas analizadas, interruptor de limitador anticorte y nivel objetivo de referencia (`-18` a `-14 LUFS`).
- **Configuración Multi-Etiqueta (`MultiTagConfig`)**: Conjunto de separadores activos (`;`, `,`, `/`, `&`, conectores `ft./feat./con/with/vs.`), interruptor de extracción desde el título, opción de ocultar el texto `(ft. ...)` ya extraído en listas y **Lista Blanca persistente** con valores predeterminados curados más entradas personalizadas del usuario.
- **Perfil de Hardware y Pantalla (`PerformanceProfile` y `AdaptiveLayoutSpec`)**: Nivel detectado/seleccionado (`ULTRA_SAVER`, `LIGHT`, `FULL`), densidad visual (`COMPACT`, `STANDARD`, `COMFORTABLE`), escala de fuente y dispositivo de salida de audio elegido en escritorio.

### Mapeo Interactivo de Componentes y Estados

- **Selector de Modo ReplayGain y Deslizadores de Preamplificación**: Al cambiar el modo o los decibelios en Ajustes, el motor recalcula de inmediato el multiplicador lineal limitado por pico de la pista activa y actualiza suavemente el volumen del canal de audio en reproducción sin reiniciar la canción.
- **Gestor de Lista Blanca y Separadores**: Añadir un artista protegido (ej. *"Crosby, Stills & Nash"*) o activar/desactivar la coma `,` reindexa en memoria las agrupaciones de Artistas y Géneros de forma asíncrona en segundo plano, actualizando instantáneamente las pestañas y los mixes inteligentes sin bloquear la interfaz.
- **Píldoras de Colaboradores en el Reproductor**: Pulsar sobre un artista individual navega directamente a su vista de detalle filtrada; si la pista tiene varios artistas y se pulsa el área general de artista en la minirreproducción, despliega un selector rápido con todos los artistas detectados (incluidos los extraídos del título).
- **Conmutador Gapless y Prioridad de Álbum**: Cuando Gapless está activo y la siguiente canción en la cola pertenece al mismo álbum con número de pista consecutivo (`N -> N+1`), el planificador prepara anticipadamente el decodificador siguiente y enlaza el flujo PCM exacto sin ejecutar fundido cruzado; al saltar manualmente o mezclar listas aleatorias, aplica Crossfade si está habilitado.
- **Selector de Salida de Audio y Perfil Ligero en PC/Android**: En Linux/Windows lista los mezcladores disponibles del sistema (ej. Intel HDA, HDMI o tarjeta USB externa bajo PipeWire/ALSA) permitiendo cambiar la salida; activar *Ultra-Ahorro* o *Modo Ligero* apaga al instante sombreadores de desenfoque, ondas continuas y marquesinas automáticas, reduciendo drásticamente el consumo en reposo.
