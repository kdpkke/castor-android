# Castor for Android privacy policy

*Versione italiana più sotto.*

Effective date: 17 November 2026

Castor for Android (`com.muratoreware.castorandroid`) is made by Matteo Muratore.
Questions and requests: [open an issue on GitHub](https://github.com/kdpkke/castor-android/issues).

## In short

- No account, no ads, no analytics, no tracking.
- The developer receives nothing from the app, unless you choose to send a debug log.
- What Castor stores stays on your phone.
- Castor connects only to the TVs on your network, to the pages and videos you choose,
  and to the few services listed below.

## What stays on your phone

- Your settings: chosen TVs and their addresses, language, appearance and casting options.
- Pairing keys for AirPlay and FCast receivers.
- Cookies and site data of the pages you open in Castor's built-in browser.
- Speech models downloaded for generated subtitles.
- The debug log, only while debug mode is on.

Android backup is turned off for Castor, so none of this is copied to a cloud backup.
Uninstalling Castor deletes it, except the debug log in **Download/Castor**, which stays
until you delete it.

## Connections Castor makes

- **TVs and receivers on your local network.** Castor looks for them on your Wi-Fi,
  sends them the address of the video it serves from your phone and controls playback.
  TVs may contact their own makers' services as they normally do.
- **The pages and videos you choose.** Castor and its built-in browser load them from
  their sites, like any browser. Those sites see your IP address and what a browser
  normally sends, and their own privacy policies apply.
- **Built-in ad blockers.** uBlock Origin and AdGuard come with Castor and keep their
  filter lists up to date from their providers' servers.
- **Built-in browser engine.** Mozilla GeckoView may download security updates, such as
  lists of dangerous sites.
- **Generated subtitles.** Only if you turn them on, Castor downloads a speech recognition
  model once from Hugging Face (`huggingface.co`). Speech is turned into text on your
  phone; no audio leaves it.
- **Updates, GitHub version only.** The app downloaded from GitHub asks GitHub at most
  every six hours whether a new version is out, and downloads it when you choose to
  update. The Google Play version does not: Google Play updates it.

## Permissions

- **Camera:** only to scan the QR code an FCast receiver shows. Images are read on the
  phone and not saved or sent.
- **Notifications:** casting status and Castor's notices, shown on the phone only.
- **Local network and Wi-Fi:** to find and reach your TVs.

## Debug mode

Debug mode is off by default. While it is on, Castor writes a log file to
**Download/Castor** (Android 10 and later) or to its own folder (Android 9). It contains
your phone model, chip, memory, video encoders and Android version, Castor's own log
lines, and the addresses of the pages, videos and TVs you used. It does not contain
passwords. The file leaves your phone only if you share it, with whomever you choose.
If you send it to the developer, it is used only to find and fix the problem and is
deleted when that is done.

## Children

Castor is not directed at children.

## Your rights

The developer holds no data about you. If you sent a debug log and want it deleted, ask
through the contact above.

## Changes

Changes to this policy are published at this address, with a new effective date.

---

# Informativa sulla privacy di Castor per Android

In vigore dal 17 novembre 2026

Castor per Android (`com.muratoreware.castorandroid`) è sviluppato da Matteo Muratore.
Domande e richieste: [apri una segnalazione su GitHub](https://github.com/kdpkke/castor-android/issues).

## In breve

- Nessun account, nessuna pubblicità, nessuna statistica, nessun tracciamento.
- Lo sviluppatore non riceve nulla dall'app, a meno che tu scelga di mandargli un log di debug.
- Ciò che Castor salva resta sul tuo telefono.
- Castor si collega solo alle TV della tua rete, alle pagine e ai video che scegli e ai
  pochi servizi elencati sotto.

## Cosa resta sul telefono

- Le tue impostazioni: TV scelte e loro indirizzi, lingua, aspetto e opzioni di trasmissione.
- Le chiavi di associazione dei ricevitori AirPlay e FCast.
- Cookie e dati dei siti delle pagine aperte nel browser integrato di Castor.
- I modelli vocali scaricati per i sottotitoli generati.
- Il log di debug, solo mentre la modalità debug è attiva.

Il backup di Android è disattivato per Castor: niente di tutto questo finisce in un backup
nel cloud. Disinstallare Castor lo cancella, tranne il log di debug in **Download/Castor**,
che resta finché non lo cancelli tu.

## Collegamenti che Castor fa

- **TV e ricevitori della tua rete locale.** Castor li cerca nel Wi-Fi, manda loro
  l'indirizzo del video che serve dal telefono e comanda la riproduzione. Le TV possono
  contattare i servizi del proprio produttore, come fanno di solito.
- **Le pagine e i video che scegli.** Castor e il suo browser integrato li caricano dai
  rispettivi siti, come qualsiasi browser. Quei siti vedono il tuo indirizzo IP e ciò che
  un browser invia di solito, e valgono le loro informative sulla privacy.
- **Ad blocker integrati.** uBlock Origin e AdGuard sono inclusi in Castor e aggiornano
  le loro liste di filtri dai server dei rispettivi fornitori.
- **Motore del browser integrato.** Mozilla GeckoView può scaricare aggiornamenti di
  sicurezza, come elenchi di siti pericolosi.
- **Sottotitoli generati.** Solo se li attivi, Castor scarica una volta un modello di
  riconoscimento vocale da Hugging Face (`huggingface.co`). Il parlato diventa testo sul
  telefono; nessun audio esce dal telefono.
- **Aggiornamenti, solo versione GitHub.** L'app scaricata da GitHub chiede a GitHub al
  massimo ogni sei ore se è uscita una nuova versione e la scarica quando scegli di
  aggiornare. La versione Google Play non lo fa: la aggiorna Google Play.

## Permessi

- **Fotocamera:** solo per inquadrare il codice QR mostrato da un ricevitore FCast. Le
  immagini sono lette sul telefono e non vengono salvate né inviate.
- **Notifiche:** stato della trasmissione e avvisi di Castor, mostrati solo sul telefono.
- **Rete locale e Wi-Fi:** per trovare e raggiungere le tue TV.

## Modalità debug

La modalità debug è spenta all'inizio. Mentre è attiva, Castor scrive un file di log in
**Download/Castor** (Android 10 e successivi) o nella propria cartella (Android 9).
Contiene modello del telefono, chip, memoria, codificatori video e versione di Android,
le righe di log di Castor e gli indirizzi delle pagine, dei video e delle TV usati. Non
contiene password. Il file esce dal telefono solo se lo condividi tu, con chi scegli. Se
lo mandi allo sviluppatore, viene usato solo per trovare e correggere il problema e poi
cancellato.

## Minori

Castor non è rivolto ai minori.

## I tuoi diritti

Lo sviluppatore non conserva dati su di te. Se hai mandato un log di debug e vuoi che
venga cancellato, chiedilo tramite il contatto sopra.

## Modifiche

Le modifiche a questa informativa vengono pubblicate a questo indirizzo, con una nuova
data di entrata in vigore.
