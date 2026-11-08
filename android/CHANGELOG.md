# Castor Android changelog

Plain-language notes for each Android release. The Italian version follows the
English one.

## 1.4.0 — Unreleased

- The TV picker lists each host once with its IP addresses and ports. Selecting a TV opens its available casting protocols, with Back below the choices.

- Cast different videos to different TVs at the same time. Switch transmissions to control one, or choose New cast to prepare another without stopping the others.
- Restart casting reloads the same source on the same TV. Videos resume from their last reported position, while live streams return to live.
- Live streams with a receiver playhead stuck for more than ten seconds restart automatically, including with the screen off. Paused streams, unknown positions and normal videos are excluded; consecutive unsuccessful recovery attempts are bounded.
- Conversions use the phone's hardware video encoder when it works. If it fails to start, dies or stops producing during a cast, Castor continues in software: on DLNA TVs fed MPEG-TS in the same stream, without the TV reloading; elsewhere from where the viewer was. A failed hardware encoder is not used again until Castor restarts.
- DLNA TVs receive finished files in Native unchanged, keeping every track and Dolby Vision signaling, and report whether they got the original file, its tracks or a conversion. On LG TVs seeking such a file now works, also while paused.
- The playback position stays right after a restart or an automatic recovery, and is no longer reported as zero when the TV stops. Seeking before where a restarted stream begins, or on a TV that refuses its own seek, prepares the stream again from that point; a Native original is never converted to seek.
- Cast to AirPlay TVs: Apple TVs and TVs with AirPlay built in, such as LG webOS models, are found on the network. The first cast to a TV that asks for pairing shows a PIN dialog; enter the PIN the TV displays, or ask it for a new one. Castor keeps the pairing on the phone.
- Cast to FCast receivers, found on the network or added by address.
- The protocol list of a TV says what a Native cast keeps on each one (the original file, its tracks, or a conversion) and recommends the one keeping the most.
- In Native, HDR and Dolby Vision files a Chromecast or AirPlay TV can fetch itself are handed to it unchanged: AirPlay TVs such as LG now show Dolby Vision. A TV that refuses the file gets Castor's stream instead.

- Reopening the same webpage for another cast loads it again after the previous player closed, instead of leaving a blank browser with an endless loading message. Retrying a page that is still open preserves the player's state.
- A saved Chromecast remains discoverable when multicast announcements disappear: Castor verifies its receiver UUID and open Cast port at the saved address. Offline receivers and reused addresses stay hidden.
- Live streams that cannot play in Native automatically retry in Optimized. The app shows the change without overwriting the saved quality; finite videos retain strict Native playback.
- Pause is temporarily unavailable while a seek is being applied. It becomes available again after the seek, without cancelling the pending request or locking the controls.
- Embedded pages allow inline frames required by widgets such as Cloudflare Turnstile, while retaining ad blocking, popup refusal and local-file restrictions.
- Native Chromecast downloads use indexed HLS fragments while the source arrives, preserving video/audio. Seeking outside the TV's available window prepares a new portion from the requested position, keeping the old playback available until ready and preserving pause. The app keeps the complete video's timeline; the TV's timer may show time within the new portion. Source access and segment/keyframe boundaries still constrain seeking. Pausing this finite playback does not finish the cast because segment requests stop.
- A failed playback command cancels the current held gesture. Controls recover automatically without dismissing the error, so they remain usable while the video continues.
- Seeking holds the current picture before preparing or verifying the requested position on receivers with pause support. The previous portion cannot keep playing while a failed receiver seek falls back to source preparation; a preparation failure restores its previous pause state.
- Chromecast recovers finite HLS playback stuck buffering at the start after a large seek. It waits for the receiver to start, tries one small recovery seek when media is available, and reports failure if the player remains stuck.
- Chromecast's initial IDLE/loading state is allowed while the new media session is being assigned. Explicit playback failures and ended sessions still fail; a loading state is not treated as a refusal.
- Control playback from the app: pause/resume, timeline, and circular ten-second back/forward arrows. Hold an arrow to keep seeking; release it to stop. Controls use the TV's reported state and capabilities, and a failed command does not end the cast.
- Pause is temporarily unavailable while a seek is in progress. Pressing it can no longer cancel an unfinished seek and leave playback controls waiting on conflicting commands.
- Embedded inline frames needed by verification widgets can load while ad blocking and popup protection stay enabled.
- Seeking or reconnecting to a video still downloading resumes at the requested byte instead of restarting from the beginning. Positions beyond the downloaded portion wait for data; initial playback continues to follow the growing stream.
- Chromecast Native playback tries the original video and audio before declaring them incompatible. Generic receiver defaults no longer prevent HEVC Main10, HDR or surround playback from reaching the TV; the receiver's playback verdict decides. Optimized conversion remains unchanged.
- Embedded webpages handle horizontal and vertical swipes without scrolling the surrounding app. Gestures outside the webpage still scroll Castor.
- HDR conversion resizes frames in its first color-conversion pass, reducing the work needed when a 4K source must be encoded for an HD receiver.
- Replace numeric quality presets with Native and Optimized, with immediate explanations in English and Italian. Native preserves video/audio without re-encoding or automatic stream changes; incompatible sources offer Optimized. Native subtitles use source captions only.
- Choose a video from Android storage or share one directly to Castor. Files stay in place and do not need a full copy before casting.
- Automatic detects extensionless video downloads from server headers and starts progressive delivery through the phone.
- Removed the retired external browser extension settings and pairing gateway. Embedded webpage extraction and ad blocking remain available.
- Updated the shared casting engine to Castor 2.5.0.

### Italiano

- La ricerca mostra ogni TV una sola volta con IP e porte. Selezionandola si apre la pagina dei protocolli disponibili, con Indietro sotto le opzioni.

- Trasmetti video diversi su TV diverse contemporaneamente. Seleziona una trasmissione per controllarla oppure usa Nuovo cast senza fermare le altre.
- Riavvia trasmissione ricarica la stessa sorgente sulla stessa TV. I video riprendono dall’ultima posizione rilevata; le dirette tornano al punto live.
- Le dirette con posizione ferma sulla TV per oltre dieci secondi vengono riavviate automaticamente, anche a schermo spento. Sono esclusi pausa, posizione sconosciuta e video normali; i recuperi consecutivi senza successo sono limitati.
- Le conversioni usano l'encoder video hardware del telefono quando funziona. Se non parte, si interrompe o smette di produrre durante una trasmissione, Castor continua in software: sulle TV DLNA che ricevono MPEG-TS nello stesso flusso, senza ricaricare la TV; altrove dal punto in cui si trovava lo spettatore. Un encoder hardware fallito non viene riusato fino al riavvio di Castor.
- Le TV DLNA ricevono i file finiti in Nativa senza modifiche, con tutte le tracce e il segnale Dolby Vision, e indicano se hanno ricevuto il file originale, le sue tracce o una conversione. Sulle TV LG lo spostamento in questi file ora funziona, anche in pausa.
- La posizione resta corretta dopo un riavvio o un recupero automatico e non viene più indicata come zero quando la TV si ferma. Uno spostamento prima dell'inizio di un flusso riavviato, o su una TV che rifiuta il proprio seek, prepara di nuovo il flusso da quel punto; un originale in Nativa non viene mai convertito per spostarsi.
- Trasmetti alle TV AirPlay: le Apple TV e le TV con AirPlay integrato, come i modelli LG webOS, vengono trovate in rete. Il primo cast verso una TV che chiede l'abbinamento apre una finestra per il PIN: inserisci quello mostrato dalla TV, oppure chiedine uno nuovo. Castor conserva l'abbinamento sul telefono.
- Trasmetti ai ricevitori FCast, trovati in rete o aggiunti per indirizzo.
- L'elenco dei protocolli di una TV indica cosa mantiene una trasmissione Nativa su ciascuno (il file originale, le sue tracce o una conversione) e consiglia quello che mantiene di più.
- In Nativa i file HDR e Dolby Vision che una TV Chromecast o AirPlay può scaricare da sé le arrivano senza modifiche: le TV AirPlay come le LG ora mostrano il Dolby Vision. Se la TV rifiuta il file, riceve il flusso di Castor.

- Riaprire la stessa pagina per un altro cast la carica nuovamente dopo la chiusura del player precedente, evitando il browser nero con caricamento infinito. Riprovare una pagina ancora aperta mantiene lo stato del player.
- L’ultima Chromecast salvata resta rilevabile quando spariscono gli annunci multicast: Castor verifica UUID del ricevitore e porta Cast aperta all’indirizzo salvato. TV offline e indirizzi riutilizzati restano nascosti.
- Le dirette che non possono essere trasmesse in Nativa vengono ritentate automaticamente in Ottimizzata. L’app segnala il cambio senza modificare la qualità salvata; i video finiti mantengono la modalità Nativa rigorosa.
- Pausa resta temporaneamente disabilitata mentre si applica uno spostamento e torna disponibile al termine, senza annullare la richiesta in corso o bloccare i controlli.
- Le pagine integrate consentono i frame inline necessari a widget come Cloudflare Turnstile, mantenendo blocco pubblicità, rifiuto dei popup e restrizioni sui file locali.
- I download in Nativa su Chromecast usano frammenti HLS indicizzati mentre arriva la sorgente, mantenendo video/audio. Uno spostamento fuori dall’intervallo disponibile sulla TV prepara una nuova porzione dal punto richiesto: la vecchia riproduzione resta disponibile fino a quando è pronta e la pausa viene conservata. L’app mantiene la barra temporale del video completo; il timer della TV può indicare il tempo nella nuova porzione. Restano i limiti di accesso alla sorgente e dei confini dei frammenti/fotogrammi chiave. La pausa non conclude il cast quando cessano le richieste dei frammenti.
- Un comando di riproduzione fallito interrompe la pressione prolungata in corso. I controlli si ripristinano automaticamente senza dover chiudere l’errore e restano utilizzabili mentre il video continua.
- Lo spostamento ferma la scena prima di preparare o verificare il punto richiesto, sui ricevitori che supportano la pausa. La porzione precedente non continua a riprodursi mentre un seek fallito passa alla preparazione dalla sorgente; se questa fallisce viene ripristinato lo stato di pausa precedente.
- Chromecast recupera la riproduzione HLS finita che resta in caricamento all’inizio dopo un salto grande. Attende l’avvio della TV, prova un solo piccolo spostamento di recupero quando i dati sono disponibili e segnala un errore se il player rimane bloccato.
- Lo stato iniziale IDLE/caricamento di Chromecast viene atteso mentre si crea la nuova sessione video. Gli errori espliciti e le sessioni terminate restano segnalati; il caricamento iniziale non viene interpretato come un rifiuto.
- Controlla la riproduzione dall’app: pausa/ripresa, barra temporale e frecce circolari per andare avanti o indietro di dieci secondi. Tieni premuta una freccia per continuare a spostarti e rilasciala per fermarti. I comandi seguono stato e capacità della TV; un comando fallito non interrompe il cast.
- Pausa è temporaneamente indisponibile durante uno spostamento nel video. La sua pressione non annulla più un seek incompleto lasciando i controlli in attesa di comandi incompatibili.
- I frame inline richiesti dai widget di verifica possono caricarsi, mantenendo attivi il blocco pubblicità e la protezione dai popup.
- Lo spostamento nel video e le riconnessioni durante il download riprendono dal byte richiesto, invece di ricominciare da capo. Le posizioni non ancora scaricate attendono i dati; la riproduzione iniziale continua a seguire il flusso in crescita.
- In modalità Nativa, Chromecast prova video e audio originali prima di dichiararli incompatibili. I limiti generici del ricevitore non bloccano più a priori HEVC Main10, HDR e audio multicanale: decide la risposta effettiva della TV. La conversione Ottimizzata mantiene il comportamento precedente.
- Le pagine integrate gestiscono lo scorrimento orizzontale e verticale senza spostare l’app. I gesti fuori dalla pagina continuano a scorrere Castor.
- La conversione HDR ridimensiona i fotogrammi nel primo passaggio colore, riducendo il lavoro quando un video 4K deve essere convertito per un ricevitore HD.
- Le modalità Nativa e Ottimizzata sostituiscono i limiti numerici, con spiegazioni immediate in inglese e italiano. Nativa mantiene video e audio senza ricodifiche o cambi automatici di flusso; se serve una conversione, propone Ottimizzata. In modalità Nativa i sottotitoli provengono solo dalla sorgente.
- Scegli un video dal dispositivo o condividilo direttamente con Castor. Il file resta nella posizione originale, senza copiarlo interamente prima di trasmettere.
- Automatico riconosce i download video senza estensione dagli header del server e usa la trasmissione progressiva dal telefono.
- Rimossi le impostazioni e il collegamento per l’estensione esterna del browser, ormai ritirata. Restano disponibili l’estrazione dalle pagine e il blocco della pubblicità.
- Motore di trasmissione aggiornato a Castor 2.5.0.

## 1.3.0 — 2026-10-27

### Casting
- While a video plays, Castor follows the speed of the TV's Wi-Fi: it sends a
  lighter picture when the connection slows down and a better one again after
  a good stretch.
- If a cast had to fall back to a less suitable link, Castor tries the better
  one again after a while of smooth playback and resumes where it was.
- On DLNA TVs, seeking with the TV's remote now works when Castor sends the
  video as a single stream with a known length and no generated subtitles.
- A TV left paused for a long time no longer loses the connection and starts
  the video over.
- The end of a video is no longer cut short: Castor waits for the TV to play
  what it has already received.
- When a page offers several links, the one whose sound is in your language
  is tried first.
- Subtitles reach every DLNA TV, Samsung included, in the standard way.

### Under the hood
- Updated to the Castor 2.4.0 casting engine.

---

## 1.3.0 — 27/10/2026 (italiano)

### Trasmissione
- Mentre un video va avanti, Castor segue la velocità del Wi-Fi della TV:
  manda un'immagine più leggera quando la connessione rallenta e di nuovo una
  migliore dopo un tratto senza problemi.
- Se una trasmissione ha dovuto ripiegare su un link meno adatto, dopo un po'
  di riproduzione regolare Castor riprova quello migliore e riprende da dove
  era.
- Sulle TV DLNA ora ci si può spostare nel video con il telecomando della TV,
  quando Castor invia il video come flusso unico di durata nota e senza
  sottotitoli generati.
- Una TV lasciata a lungo in pausa non perde più la connessione e non
  ricomincia il video dall'inizio.
- La fine di un video non viene più tagliata: Castor aspetta che la TV
  riproduca quello che ha già ricevuto.
- Quando una pagina offre più link, viene provato per primo quello con l'audio
  nella tua lingua.
- I sottotitoli arrivano a tutte le TV DLNA, Samsung comprese, nel modo
  standard.

### Dietro le quinte
- Aggiornato al motore di trasmissione di Castor 2.4.0.

---

## 1.2.1 — 2026-10-23

### Casting
- A live stream whose access expires right after the cast starts is now
  renewed reliably. Before, Castor could fail to reopen the page and stop the
  cast for good.

### Under the hood
- Up to date with Castor 2.3.0; the casting engine is the same as in 1.2.0.

---

## 1.2.1 — 23/10/2026 (italiano)

### Trasmissione
- Una diretta il cui accesso scade subito dopo l'avvio della trasmissione ora
  viene rinnovata in modo affidabile. Prima Castor poteva non riuscire a
  riaprire la pagina e interrompere la trasmissione per sempre.

### Dietro le quinte
- Allineato a Castor 2.3.0; il motore di trasmissione è lo stesso della 1.2.0.

---

## 1.2.0 — 2026-10-22

### Cast from Firefox
- Works with the new **Castor extension for Firefox** on this phone: a beaver
  appears on the videos you watch in Firefox; tap it, choose your TV and the
  quality, and Castor sends the video.
- The first time, Castor asks in a notification whether Firefox may cast. Tap
  **Allow**. If you open Castor instead, it asks the same question there.
- **Browser extension** in the menu shows how many browsers you allowed and
  removes their permission with one tap.
- Keep Castor open while you cast from Firefox. If it is closed, the extension
  offers to open it.

### Casting
- When a TV accepts a video in more than one way, Castor keeps the picture as
  it is and converts only the sound if needed: less work for the phone and the
  original picture quality.
- FLV videos are recognized straight away.

### Under the hood
- Updated to the Castor 2.1 casting engine, including the fixes for HLS streams
  and for Stop that 1.1.0 carried on its own.

---

## 1.2.0 — 22/10/2026 (italiano)

### Trasmettere da Firefox
- Funziona con la nuova **estensione Castor per Firefox** su questo telefono:
  sui video che guardi in Firefox compare un castoro; toccalo, scegli la TV e
  la qualità, e Castor invia il video.
- La prima volta Castor chiede con una notifica se Firefox può trasmettere.
  Tocca **Consenti**. Se invece apri Castor, la stessa domanda compare lì.
- **Estensione del browser** nel menu mostra quanti browser hai autorizzato e
  revoca il loro permesso con un tocco.
- Lascia Castor aperto mentre trasmetti da Firefox. Se è chiuso, l'estensione
  propone di aprirlo.

### Trasmissione
- Quando una TV accetta un video in più modi, Castor lascia l'immagine com'è e
  converte solo l'audio se serve: meno lavoro per il telefono e la qualità
  d'immagine originale.
- I video FLV vengono riconosciuti subito.

### Dietro le quinte
- Aggiornato al motore di trasmissione Castor 2.1, con le correzioni per le
  dirette HLS e per Stop che la 1.1.0 aveva incluso per conto suo.

---

## 1.1.0 — 2026-10-22

**Before updating:** this version is signed with a new key. Uninstall Castor
1.0.0 first, then install 1.1.0. Uninstalling removes your saved TV, language,
appearance and playback settings; choose them again once. Later updates will
install normally.

### Ad-free page player
- Web pages now open in a built-in Firefox-based player with **uBlock Origin**
  and **AdGuard** switched on. Pop-ups and ad pages no longer replace the video.
- Ads are never sent to your TV: only videos the page really plays are cast.
- If Castor does not find the video, the page stays open. Press Play in the page,
  then tap **Cast video** again, without reloading.
- Page errors are explained clearly, in English or Italian.

### Finding your TV
- TVs connected to your phone's **hotspot** are found, and TVs on your home Wi-Fi
  stay visible while the hotspot is on.
- Your last TV is shown first, with the others under **Other TVs**.
- When you open the app, Castor checks that your saved TV is still on the
  network. If it is not, it tells you instead of trying to cast to it.
- A TV is recognized even after it changes address, for example after a restart.

### Casting
- Pressing **Stop** no longer shows an error.
- More live streams play, including some that were refused before.
- If you reopen Castor while it is casting, you see that cast and can stop it.
- The app no longer stops casting after a short slowdown of the phone.

### Settings
- **Native quality** is the default: the TV gets the video's own resolution.
- Settings have a **Save** button. If you close them with unsaved changes,
  Castor asks whether to save them.
- Language, appearance, TV and playback settings are kept when you close the app.

### Under the hood
- Updated to the Castor 2.0.0 casting engine.
- The app is larger than 1.0.0 because it includes its own browser engine.

---

## 1.1.0 — 22/10/2026 (italiano)

**Prima di aggiornare:** questa versione è firmata con una chiave nuova.
Disinstalla prima Castor 1.0.0, poi installa la 1.1.0. Disinstallando perdi TV,
lingua, aspetto e impostazioni di riproduzione salvati: andranno scelti di nuovo
una volta. I prossimi aggiornamenti si installeranno normalmente.

### Lettore delle pagine senza pubblicità
- Le pagine web si aprono in un lettore integrato basato su Firefox, con
  **uBlock Origin** e **AdGuard** attivi. Popup e pagine pubblicitarie non
  sostituiscono più il video.
- Le pubblicità non arrivano mai alla TV: viene trasmesso solo il video che la
  pagina riproduce davvero.
- Se Castor non trova il video, la pagina resta aperta: avvia il video nella
  pagina e tocca di nuovo **Trasmetti sulla TV**, senza ricaricarla.
- Gli errori delle pagine sono spiegati chiaramente, in italiano o in inglese.

### Trovare la TV
- Vengono trovate anche le TV collegate all'**hotspot** del telefono, e quelle
  sul Wi-Fi di casa restano visibili mentre l'hotspot è acceso.
- L'ultima TV usata compare per prima, le altre sotto **Altre TV**.
- All'apertura Castor controlla che la TV salvata sia ancora in rete; se non c'è,
  lo dice invece di provare a trasmettere.
- Una TV viene riconosciuta anche dopo che cambia indirizzo, per esempio dopo un
  riavvio.

### Trasmissione
- Premere **Stop** non mostra più un errore.
- Più dirette vengono riprodotte, comprese alcune rifiutate in precedenza.
- Se riapri Castor mentre sta trasmettendo, vedi la trasmissione in corso e puoi
  fermarla.
- L'app non interrompe più la trasmissione per un breve rallentamento del
  telefono.

### Impostazioni
- La **Qualità nativa** è il valore predefinito: la TV riceve il video alla sua
  risoluzione originale.
- Le impostazioni hanno il pulsante **Salva**. Se le chiudi con modifiche non
  salvate, Castor chiede se vuoi salvarle.
- Lingua, aspetto, TV e impostazioni di riproduzione restano salvate alla
  chiusura dell'app.

### Dietro le quinte
- Aggiornato al motore di trasmissione Castor 2.0.0.
- L'app è più grande della 1.0.0 perché include un proprio motore browser.

## 1.0.0 — 2026-10-19

First stable release: casting from the phone without a PC, English and Italian,
light and dark appearance, native quality and subtitles.
