# Monitor Palestra - Design system "Keep Gold"

Fonte visiva: logo Keep Gold (oro su nero, corona d'alloro, stella, scritta nera pesante in maiuscolo).
Solo tema scuro: il marchio e' oro su nero e un tema chiaro snaturerebbe il logo. Il nero evita anche l'abbagliamento in palestra.
Nessuna risorsa esterna: font di sistema, CSS puro in `tokens.css` (da incollare inline).

## Colori
| Token | Hex | Origine nell'immagine |
|---|---|---|
| `--kg-black` | #0B0B0B | fondo nero a lavagna |
| `--kg-surface` / `-2` | #161513 / #211F1B | nero caldo derivato, per card e controlli |
| `--kg-gold` | #EAB92A | foglie d'alloro e nastro (azione primaria) |
| `--kg-gold-deep` | #C49A2A | ombra delle facce della stella (premuto) |
| `--kg-gold-pale` | #F0E08F | bordo chiaro del nastro (focus) |
| `--kg-white` | #FFFFFF | contorno bianco "sticker" (testo) |
| `--kg-text-muted` | #B0ACA0 | testo secondario |
| `--kg-ok` / `--kg-danger` | #5FD38D / #FF7A6B | solo stati; non sono nel logo |

Contrasto: oro su nero circa 10:1, testo nero su oro circa 10:1, muted su card circa 8:1 (tutto AA).

## Tipografia
Stack di sistema (`-apple-system`, `system-ui`). Titoli: peso 900, MAIUSCOLO, come la scritta del logo. Corpo: 500.
Scala: 13 / 15 / 17 (corpo) / 20 / 28 px (`--fs-xs` ... `--fs-xl`).

## Forme e spazi
Griglia 4px (`--sp-1..6`). Angoli grandi 16px (`--radius`), pillola per toggle e barre. Bersagli tocco 48px (`--touch`).

## Componenti (classi)
- Header: `.header` `.title` `.meter` `.meter-bar` `.meter-fill` (`.is-low` sotto soglia)
- Pulsanti: `.btn` + `.btn-primary` (carica), `.btn-secondary` (riavvia), `.btn-danger`, `.btn-quiet`
- Upload: `.upload` `.upload-fill` (imposta `--pct`)
- Lista: `.media-list` `.media-item` (`.is-off`, `.is-dragging`) `.handle` `.thumb` `.media-body` `.icon-btn`
- Controlli: `.switch` (input + `.track`), `.stepper` (due `button` + `output`)
- Impostazioni: `.settings` `.row` `.row-text` `.row-hint`
- Stato vuoto: `.empty`
- Avvisi: `.toast-zone` + `.toast.toast-ok` / `.toast.toast-error`
- Conferma: `.scrim` + `.dialog` `.dialog-actions`

## Da fare
- Un solo pulsante oro per schermata: "Carica video o foto".
- Eliminare sempre con dialogo di conferma; "Annulla" in evidenza e "Elimina" come ultima azione.
- Testi semplici in italiano, verbo all'infinito sul pulsante: "Carica", "Elimina", "Riavvia".
- Stato on/off sempre con interruttore piu' testo ("Spento, non viene mostrato"), mai solo colore.
- Toast sotto il pollice, errori con cosa fare ("Controlla il Wi-Fi e riprova").

## Da non fare
- Niente font o file esterni (il server e' offline).
- Niente oro come testo piccolo su superfici chiare o come sfondo di piu' pulsanti insieme.
- Niente rosso per azioni normali: il rosso e' solo per eliminare ed errori.
- Niente bersagli sotto i 44px, niente gesti nascosti: il riordino ha sempre la maniglia visibile.
