package com.example.hextool

import java.net.IDN
import java.net.URLDecoder
import java.text.Normalizer
import java.util.Locale

/**
 * Pemeriksa keamanan tautan / isi QR sepenuhnya lokal (tanpa server / API).
 *
 * Prinsip desain: UTAMAKAN TIDAK ADA FALSE NEGATIVE.
 *  - Ambang batas sengaja rendah; lebih baik memberi peringatan berlebih daripada meloloskan phishing.
 *  - Level AMAN tidak berarti "pasti aman", hanya "tidak ada sinyal risiko dari aturan lokal".
 *    Pemeriksaan lokal tidak bisa melihat umur domain, isi halaman, atau daftar hitam terbaru.
 *  - Sinyal yang sangat kuat (meniru merek, file APK, trik '@', IP samar, dsb.) memaksa skor
 *    minimal ke level MENCURIGAKAN apa pun sinyal lainnya.
 *
 * Sinyal disusun dari pola phishing yang beredar (Indonesia & global): APK "undangan/resi/tilang/
 * BPJS", bansos & THR, e-tilang & cek resi palsu, kloning internet banking & e-wallet, hadiah /
 * dana kaget, judi online, wallet-drainer kripto, formulir Google / situs gratisan sebagai
 * penampung halaman palsu, shortener, TLD murah yang sering disalahgunakan (.xin .bond .help
 * .win .cfd .top .xyz ...), homograf IDN, dan kit phishing di situs WordPress yang diretas.
 */
object LinkSafetyChecker {

    enum class Level { AMAN, HATI_HATI, MENCURIGAKAN }

    data class Report(
        val level: Level,
        val score: Int,          // 0 = tidak ada sinyal, makin tinggi makin berisiko
        val reasons: List<String>,
        val host: String,
        val scheme: String,
        val normalized: String,
        val trusted: Boolean = false, // host cocok dengan domain resmi merek yang dikenal
        val urlCount: Int = 1
    )

    const val THRESHOLD_SUSPECT = 35
    const val THRESHOLD_CAUTION = 12

    // ------------------------------------------------------------------
    //  DATA
    // ------------------------------------------------------------------

    private class Brand(val name: String, val keywords: List<String>, val official: List<String>)

    private fun b(name: String, kws: String, doms: String) =
        Brand(name, kws.split(','), doms.split(','))

    private val BRANDS: List<Brand> = listOf(
        // --- Bank & fintech Indonesia ---
        b("BCA", "bca,klikbca,mybca,bcamobile",
            "bca.co.id,klikbca.com,klikbca.co.id,mybca.co.id,bcadigital.co.id,blubybcadigital.id,bcasekuritas.co.id,bcafinance.co.id,bcainsurance.co.id"),
        b("BRI", "bri,brimo,briva,qlola", "bri.co.id,bri.id"),
        b("Bank Mandiri", "mandiri,bankmandiri,livinbymandiri",
            "bankmandiri.co.id,mandiri.co.id,mandirisekuritas.co.id,mandiriinhealth.co.id"),
        b("BNI", "bni,bnidirect", "bni.co.id,bni.id,bnisekuritas.co.id,bnilife.co.id"),
        b("BTN", "btn", "btn.co.id,btn.id"),
        b("CIMB Niaga", "cimb,cimbniaga", "cimbniaga.co.id,cimbniaga.com,cimb.com"),
        b("Bank Danamon", "danamon", "danamon.co.id"),
        b("Bank Permata", "permatabank,permatamobile", "permatabank.com,permatabank.co.id"),
        b("BSI", "bankbsi,bsimobile", "bankbsi.co.id,bsi.co.id"),
        b("Bank Jago", "bankjago", "jago.com,bankjago.com"),
        b("SeaBank", "seabank", "seabank.co.id"),
        b("Jenius / BTPN", "jenius,btpn", "jenius.com,btpn.com"),
        b("OCBC", "ocbc", "ocbc.id,ocbc.com"),
        b("Maybank", "maybank", "maybank.co.id,maybank.com"),
        b("DANA", "dana,danakaget", "dana.id"),
        b("OVO", "ovo,ovoid", "ovo.id,ovo.co.id"),
        b("GoPay / Gojek", "gopay,gojek", "gojek.com,gopay.co.id,gopay.com,go-jek.com"),
        b("ShopeePay / Shopee", "shopee,shopeepay,shopeefood,spaylater",
            "shopee.co.id,shopee.com,shopee.sg,shopee.ph,shopee.vn,shopee.tw,shopee.com.my,shopee.co.th,shopeepay.co.id,shopeefood.co.id,shopee.id,shp.ee"),
        b("LinkAja", "linkaja", "linkaja.id"),
        b("Tokopedia", "tokopedia,tokped", "tokopedia.com,tokopedia.link,tokopedia.id"),
        b("Lazada", "lazada", "lazada.co.id,lazada.com,lazada.sg,lazada.com.my,lazada.com.ph,lazada.co.th,lazada.vn"),
        b("Bukalapak", "bukalapak", "bukalapak.com"),
        b("Blibli", "blibli", "blibli.com"),
        b("Traveloka", "traveloka", "traveloka.com"),
        b("Grab", "grabfood,grabpay", "grab.com,grab.id"),
        b("Akulaku", "akulaku", "akulaku.com"),
        b("Kredivo", "kredivo", "kredivo.com"),
        b("Pegadaian", "pegadaian", "pegadaian.co.id"),
        b("Taspen", "taspen", "taspen.co.id"),
        b("Alfamart", "alfamart,alfagift", "alfamart.co.id,alfagift.id"),
        b("Indomaret", "indomaret", "indomaret.co.id,klikindomaret.com"),
        b("McDonald's", "mcdonalds,mcdonald", "mcdonalds.co.id,mcdonalds.com"),
        b("KFC", "kfc", "kfc.co.id,kfc.com"),
        b("Starbucks", "starbucks", "starbucks.co.id,starbucks.com"),
        // --- Ekspedisi (modus "cek resi / paket") ---
        b("JNE", "jne", "jne.co.id,jne.id"),
        b("J&T Express", "jnt,jntexpress,jtexpress,jetexpress",
            "jet.co.id,jet.id,jtexpress.co.id,jtexpress.id,jntexpress.id,jtexpress.com"),
        b("SiCepat", "sicepat", "sicepat.com"),
        b("AnterAja", "anteraja", "anteraja.id"),
        b("SPX Express", "spx,spxexpress", "spx.co.id,spx.id,spxexpress.co.id"),
        b("POS Indonesia", "posindonesia,kantorpos", "posindonesia.co.id"),
        b("Ninja Xpress", "ninjaxpress,ninjavan", "ninjaxpress.co,ninjavan.co"),
        b("Lion Parcel", "lionparcel", "lionparcel.com"),
        b("DHL", "dhl", "dhl.com,dhl.co.id,dhl.de,dhl.co.uk,dhl.fr,dhl.it,dhl.es"),
        b("FedEx", "fedex", "fedex.com"),
        b("UPS", "ups", "ups.com"),
        b("USPS", "usps", "usps.com"),
        // --- Utilitas & operator ---
        b("PLN", "pln,plnmobile", "pln.co.id,pln.id"),
        b("Pertamina", "pertamina,mypertamina", "pertamina.com,mypertamina.id,pertamina.id"),
        b("Telkomsel", "telkomsel,mytelkomsel", "telkomsel.com,telkomsel.id"),
        b("Indosat", "indosat,indosatooredoo,im3", "indosatooredoo.com,indosat.com,im3.id"),
        b("XL Axiata", "xlaxiata,myxl,axiata", "xl.co.id,xlaxiata.co.id"),
        b("Smartfren", "smartfren", "smartfren.com"),
        b("IndiHome / Telkom", "indihome,telkom", "indihome.co.id,indihome.com,telkom.co.id,telkom.id"),
        b("OLX", "olx", "olx.co.id,olx.com"),
        // --- Instansi pemerintah RI (modus bansos / e-tilang / pajak / BPJS / CPNS) ---
        b("Instansi pemerintah RI",
            "bpjs,bpjskesehatan,bpjsketenagakerjaan,kemensos,bansos,dtks,pajak,djp,coretax,samsat,polri,tilang,etilang,etle," +
                "cpns,sscasn,prakerja,kemnaker,kemenkeu,kemendikbud,kemenag,dukcapil,pedulilindungi,satusehat,kominfo,komdigi," +
                "ojk,bankindonesia,bpom,imigrasi,paspor,baznas,kartuprakerja,kemenkes,kemenhub,korlantas,mahkamahagung,kejaksaan",
            "go.id"),
        // --- Global ---
        b("PayPal", "paypal", "paypal.com,paypal.me,paypalobjects.com"),
        b("Google", "google,gmail,googlemail",
            "google.com,google.co.id,google.co.uk,google.co.jp,google.de,google.fr,google.ca,google.com.au,google.com.sg,google.com.my," +
                "googleapis.com,gstatic.com,gmail.com,goo.gl,g.co,withgoogle.com,forms.gle,googleusercontent.com"),
        b("YouTube", "youtube,youtu", "youtube.com,youtu.be,youtube-nocookie.com"),
        b("Microsoft", "microsoft,outlook,office365,onedrive,sharepoint,microsoftonline,hotmail",
            "microsoft.com,live.com,office.com,office365.com,outlook.com,microsoftonline.com,windows.com,msn.com,bing.com," +
                "xbox.com,hotmail.com,onedrive.com,1drv.ms,sharepoint.com,skype.com,azure.com"),
        b("Apple", "apple,icloud,appleid", "apple.com,icloud.com,me.com,mzstatic.com,apple.co"),
        b("Facebook / Meta", "facebook,facebok,messenger",
            "facebook.com,fb.com,fb.me,fbcdn.net,fbsbx.com,messenger.com,m.me,meta.com,facebookmail.com"),
        b("Instagram", "instagram", "instagram.com,ig.me,cdninstagram.com,instagr.am"),
        b("WhatsApp", "whatsapp", "whatsapp.com,whatsapp.net,wa.me"),
        b("Telegram", "telegram", "telegram.org,telegram.me,t.me,telegra.ph,telesco.pe,tdesktop.com"),
        b("TikTok", "tiktok", "tiktok.com,tiktokv.com,tiktokcdn.com,tiktokshop.com"),
        b("X / Twitter", "twitter", "twitter.com,x.com,t.co,twimg.com"),
        b("Netflix", "netflix", "netflix.com"),
        b("Spotify", "spotify", "spotify.com,scdn.co,spotify.link"),
        b("Amazon", "amazon",
            "amazon.com,amazon.co.uk,amazon.de,amazon.co.jp,amazon.in,amazon.ca,amazon.fr,amazon.it,amazon.es,amazon.com.au,amazon.sg,amazonaws.com,a.co,amzn.to,amzn.com"),
        b("eBay", "ebay", "ebay.com"),
        b("Steam", "steampowered,steamcommunity", "steampowered.com,steamcommunity.com,steamstatic.com"),
        b("Discord", "discord,discordapp", "discord.com,discord.gg,discordapp.com,discord.gift,discord.media"),
        b("Roblox", "roblox", "roblox.com,rbxcdn.com"),
        b("Epic Games", "epicgames", "epicgames.com"),
        b("Garena / Free Fire", "garena,freefire", "garena.com,garena.co.id,freefiremobile.com"),
        b("Mobile Legends", "mobilelegends,moonton", "mobilelegends.com,moonton.com"),
        b("Binance", "binance", "binance.com,binance.me,bnbstatic.com,binance.us"),
        b("Coinbase", "coinbase", "coinbase.com"),
        b("MetaMask", "metamask", "metamask.io"),
        b("Trust Wallet", "trustwallet", "trustwallet.com"),
        b("Indodax", "indodax", "indodax.com"),
        b("Tokocrypto", "tokocrypto", "tokocrypto.com"),
        b("LinkedIn", "linkedin", "linkedin.com,lnkd.in,licdn.com"),
        b("Zoom", "zoom", "zoom.us,zoom.com,zoomgov.com"),
        b("DocuSign", "docusign", "docusign.com,docusign.net"),
        b("Adobe", "adobe", "adobe.com,acrobat.com"),
        b("Dropbox", "dropbox", "dropbox.com,db.tt"),
        b("WeTransfer", "wetransfer", "wetransfer.com,we.tl"),
        b("AliExpress / Alibaba", "aliexpress,alibaba", "aliexpress.com,alibaba.com,alicdn.com"),
        b("Temu", "temu", "temu.com"),
        b("Shein", "shein", "shein.com"),
        b("Western Union", "westernunion", "westernunion.com"),
        b("Payoneer", "payoneer", "payoneer.com")
    )

    // Kata penyerta yang lazim ditempel pada nama merek (bca-id, bripromo, myovo, dana-kaget, dst.).
    private val AFFIX = setOf(
        "id", "indonesia", "bank", "online", "mobile", "update", "secure", "security", "login", "signin", "klik", "pay",
        "app", "apk", "official", "resmi", "info", "cs", "help", "care", "service", "support", "com", "my", "new", "baru",
        "verif", "verify", "verifikasi", "promo", "gratis", "free", "hadiah", "bonus", "reward", "cash", "wallet",
        "account", "akun", "web", "site", "portal", "center", "store", "shop", "asia", "global", "ind", "indo", "idn",
        "mobi", "m", "i", "e", "go", "get", "claim", "klaim", "voucher", "tagihan", "bayar", "cek", "check", "status",
        "pusat", "layanan", "customer", "contact", "kontak", "hubungi", "wa", "whatsapp", "live", "team", "tim", "admin",
        "member", "vip", "pro", "plus", "lite", "digital", "net", "link", "kaget", "saldo", "topup", "limit", "blokir",
        "aktivasi", "undian", "event", "program", "cair", "pencairan", "bantuan", "daftar", "registrasi", "otp", "pin"
    )

    private val SHORTENERS = setOf(
        "bit.ly", "bitly.com", "t.co", "goo.gl", "tinyurl.com", "ow.ly", "is.gd", "cutt.ly", "rebrand.ly", "shorturl.at",
        "rb.gy", "tiny.cc", "buff.ly", "adf.ly", "shorte.st", "bc.vc", "clk.ink", "s.id", "t.ly", "lnkd.in", "trib.al",
        "v.gd", "soo.gd", "qr.ae", "1url.com", "tr.ee", "tiny.one", "urlz.fr", "shrtco.de", "bl.ink", "snip.ly",
        "short.io", "ouo.io", "ouo.press", "bit.do", "mcaf.ee", "x.co", "u.to", "lc.cx", "kutt.it", "wa.link",
        "lnk.bio", "lnk.to", "linkr.bio", "qrco.de", "qr.codes", "shorturl.asia", "gg.gg", "ln.run", "v.ht", "clck.ru",
        "vk.cc", "cli.re", "s.shopee.co.id.invalid", "t2m.io", "shorturl.com", "tinyurl.is", "zpr.io", "yourls.org",
        "rebrand.ly", "link.tl", "hyperurl.co", "smarturl.it", "j.mp", "bom.so", "han.gl", "urlzs.com", "l.ead.me",
        "tiny.id", "short.cm", "pxl.to", "dub.sh", "dub.co", "spoo.me", "sbz.ly", "l1nq.com"
    )

    // Layanan gratisan / konten buatan pengguna: domainnya "bersih" tetapi isinya bisa dibuat siapa saja.
    private val UGC_HOSTS = setOf(
        "weebly.com", "wixsite.com", "wixstudio.io", "blogspot.com", "blogspot.co.id", "wordpress.com", "github.io",
        "gitlab.io", "netlify.app", "vercel.app", "pages.dev", "workers.dev", "web.app", "firebaseapp.com",
        "herokuapp.com", "glitch.me", "repl.co", "replit.app", "replit.dev", "000webhostapp.com", "infinityfreeapp.com",
        "epizy.com", "rf.gd", "byethost7.com", "sites.google.com", "docs.google.com", "drive.google.com",
        "storage.googleapis.com", "storage.cloud.google.com", "googleusercontent.com", "canva.site", "my.canva.site",
        "canva.com", "notion.site", "notion.so", "carrd.co", "linktr.ee", "bio.link", "lynk.id", "beacons.ai",
        "webflow.io", "framer.website", "framer.app", "godaddysites.com", "ngrok.io", "ngrok-free.app", "ngrok.app",
        "trycloudflare.com", "duckdns.org", "no-ip.org", "ddns.net", "hopto.org", "zapto.org", "serveo.net", "loca.lt",
        "r2.dev", "s3.amazonaws.com", "amazonaws.com", "cloudfront.net", "blob.core.windows.net", "azurewebsites.net",
        "azurestaticapps.net", "windows.net", "ipfs.io", "dweb.link", "cloudflare-ipfs.com", "fleek.co", "surge.sh",
        "onrender.com", "railway.app", "fly.dev", "deno.dev", "appspot.com", "run.app", "myshopify.com", "square.site",
        "jimdosite.com", "strikingly.com", "mystrikingly.com", "yolasite.com", "webnode.page", "site123.me",
        "jotform.com", "typeform.com", "forms.office.com", "forms.gle", "sharepoint.com", "1drv.ms", "mediafire.com",
        "mega.nz", "dropbox.com", "wetransfer.com", "we.tl", "telegra.ph", "gitbook.io", "bubbleapps.io",
        "typedream.site", "wuaze.com", "kesug.com", "rf.gd", "42web.io", "lovable.app", "v0.dev", "bolt.host",
        "hostingerapp.com", "ukit.me", "tripod.com", "angelfire.com", "sway.office.com", "airtable.com", "tiiny.site",
        "zohosites.com", "cloudflarestorage.com", "web.core.windows.net", "pythonanywhere.com", "weeblysite.com"
    )

    private val MESSAGING_HOSTS = setOf(
        "wa.me", "wa.link", "api.whatsapp.com", "chat.whatsapp.com", "t.me", "telegram.me", "telegram.dog", "line.me",
        "lin.ee", "discord.gg", "signal.me", "m.me", "ig.me"
    )

    // TLD dengan tingkat penyalahgunaan tinggi (laporan Interisle / Spamhaus; tambahan pola di Indonesia).
    private val HIGH_TLDS = setOf(
        "xin", "bond", "help", "win", "cfd", "top", "xyz", "icu", "cyou", "sbs", "rest", "click", "buzz", "vip", "bid",
        "loan", "cam", "monster", "quest", "gdn", "pw", "su", "tk", "ml", "ga", "cf", "gq", "zip", "mov", "work",
        "support", "men", "date", "party", "stream", "review", "download", "racing", "trade", "accountant", "science",
        "cricket", "faith", "webcam", "kim", "country", "om", "cm", "con", "cim", "vom"
    )
    private val TOP_TLDS = setOf("xin", "bond", "help", "win", "cfd")
    private val MEDIUM_TLDS = setOf(
        "shop", "store", "online", "site", "live", "fun", "space", "website", "life", "today", "world", "one", "link",
        "info", "biz", "ws", "cc", "lol", "beauty", "homes", "autos", "boats", "mom", "tech", "digital", "cn", "ru",
        "club", "mobi", "ink", "pro", "asia", "co", "in", "me", "to", "tv", "pics", "pictures", "wiki", "rocks", "ltd",
        "center", "world", "agency", "services", "solutions", "group", "bar", "cloud", "network", "email", "xn--"
    )

    private val MULTI_SUFFIX = setOf(
        "co.id", "or.id", "go.id", "ac.id", "sch.id", "web.id", "my.id", "biz.id", "net.id", "desa.id", "ponpes.id",
        "mil.id", "co.uk", "org.uk", "com.au", "net.au", "co.nz", "com.sg", "com.my", "co.th", "co.in", "com.br",
        "com.mx", "com.ar", "com.tr", "com.cn", "com.hk", "com.tw", "co.jp", "co.kr", "co.za", "com.ph", "com.vn",
        "com.pk", "com.ng", "co.ke", "com.ua", "com.bd", "com.eg", "com.sa"
    )
    private val CC_SECOND = setOf("co", "com", "net", "org", "gov", "edu", "ac", "or", "go", "sch")

    // Kata di host/path yang lazim dipakai halaman palsu.
    private val CRED_WORDS = setOf(
        "login", "signin", "logon", "verify", "verification", "verifikasi", "verifikasi", "secure", "security", "account",
        "akun", "update", "confirm", "konfirmasi", "validate", "validasi", "auth", "authenticate", "password", "passwd",
        "sandi", "pin", "otp", "cvv", "kyc", "recover", "recovery", "restore", "unlock", "unblock", "suspend",
        "suspended", "limited", "blokir", "blocked", "banned", "aktivasi", "activation", "reset", "billing", "invoice",
        "payment", "bayar", "pembayaran", "tagihan", "wallet", "seed", "mnemonic", "phrase", "walletconnect",
        "airdrop", "claim", "helpdesk", "support", "customer", "cs", "official", "resmi", "service", "layanan",
        "ibanking", "ibank", "mbanking", "internetbanking", "klikbca", "token", "dompet"
    )
    private val LURE_WORDS = setOf(
        "gratis", "free", "gift", "hadiah", "prize", "undian", "giveaway", "bonus", "promo", "voucher", "reward",
        "cashback", "diskon", "discount", "cair", "pencairan", "saldo", "kaget", "jackpot", "winner", "menang",
        "lottery", "subsidi", "bansos", "undangan", "invitation", "resi", "paket", "parcel", "tracking", "lacak",
        "tilang", "denda", "surat", "panggilan", "pengadilan", "lamaran", "loker", "lowongan", "diamond", "skin",
        "nitro", "robux", "topup", "thr", "klaim", "pemenang", "kuota", "pulsa", "poin", "points", "mileage",
        "lelang", "investasi", "profit", "cuan", "trading", "cryptocurrency", "airdrop", "mint", "pinjaman", "pinjol",
        "kredit", "limit", "naik", "pelunasan", "wisuda", "pernikahan", "wedding", "foto", "video", "viral"
    )
    private val GAMBLING_PREFIX = listOf(
        "slot", "gacor", "maxwin", "togel", "judi", "casino", "sbobet", "parlay", "bandar", "scatter", "pragmatic",
        "mahjong", "taruhan", "sportsbook", "rtp", "zeus", "olympus", "bet365", "joker123", "dewa", "pokerqq", "dominoqq",
        "bandarq", "gates"
    )

    // Ekstensi berbahaya pada tautan / nama berkas.
    private val EXT_HIGH = setOf(
        "apk", "xapk", "apks", "exe", "msi", "scr", "bat", "cmd", "pif", "vbs", "vbe", "jse", "wsf", "hta", "lnk", "jar",
        "dll", "ps1", "iso", "img", "dmg", "pkg", "crx", "appx", "msix", "cab", "reg", "docm", "xlsm", "pptm", "dotm",
        "apk1", "bin", "run", "command"
    )
    private val FILE_AS_TLD = setOf(
        "apk", "xapk", "apks", "exe", "msi", "scr", "bat", "cmd", "pif", "vbs", "jar", "dll", "ps1", "iso", "dmg", "pkg",
        "lnk", "hta"
    )
    private val EXT_MEDIUM = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "z", "ace", "arj")
    private val EXT_PAGE = setOf("html", "htm", "shtml", "xhtml", "svg", "xml")
    private val EXT_SAFE_BARE = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "pdf", "txt", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "mp3",
        "mp4", "csv", "json", "log", "md", "ini", "cfg", "yml", "yaml", "kt", "java", "py", "js", "css", "wav", "avi",
        "mkv", "mov2", "heic", "svg2", "tmp", "bak", "kts", "gradle", "xml2"
    )

    private val REDIRECT_PARAMS = setOf(
        "url", "u", "link", "redirect", "redirect_uri", "redirect_url", "redir", "next", "goto", "return", "returnurl",
        "return_to", "dest", "destination", "target", "continue", "q", "to", "out", "ref", "r", "forward", "callback",
        "fallback", "service", "success_url", "link_url", "aurl", "uri"
    )
    private val EMAIL_PARAMS = setOf("email", "e", "mail", "user", "username", "usr", "id", "login", "account", "to")

    private val URGENCY_CUES = listOf(
        "segera", "24 jam", "1x24", "2x24", "dalam waktu", "batas waktu", "terakhir", "diblokir", "dibekukan",
        "ditangguhkan", "akan ditutup", "akun anda", "akun kamu", "verifikasi", "konfirmasi", "klik link", "klik tautan",
        "klik di sini", "klik disini", "anda menang", "selamat anda", "selamat kamu", "hadiah", "undian", "pemenang",
        "bansos", "pencairan", "tilang", "denda", "urgent", "immediately", "suspended", "verify your", "confirm your",
        "your account", "click here", "limited time", "act now", "you have won", "unusual activity", "kode otp",
        "jangan berikan", "terbatas", "gratis", "dana kaget", "cek resi", "paket anda", "paket kamu", "kurir"
    )

    private val ZERO_WIDTH = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\uFEFF\\u00AD\\u034F\\u180E]")
    private val SCHEME_RE = Regex("^([a-zA-Z][a-zA-Z0-9+.\\-]*)://")
    private val SPECIAL_SCHEME_RE = Regex("^([a-zA-Z][a-zA-Z0-9+.\\-]{1,24}):(?!//)(.*)$", RegexOption.DOT_MATCHES_ALL)
    private val URL_RE = Regex(
        "(?i)(?<![a-z0-9])(?:[a-z][a-z0-9+.\\-]*://[^\\s<>\"'`]+|www\\.[^\\s<>\"'`]+|" +
            "(?:[a-z0-9\\u00a1-\\uffff](?:[a-z0-9\\u00a1-\\uffff\\-]*[a-z0-9\\u00a1-\\uffff])?\\.)+" +
            "[a-z\\u00a1-\\uffff]{2,24}(?::\\d{1,5})?(?:[/?#][^\\s<>\"'`]*)?)"
    )
    private val IPV4_RE = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

    // ------------------------------------------------------------------
    //  API
    // ------------------------------------------------------------------

    private class Acc {
        var score = 0
        var floor = 0
        val reasons = ArrayList<String>()
        var trusted = false
        fun add(pts: Int, msg: String) { score += pts; reasons += msg }
        /** Sinyal kuat: tambah poin dan paksa skor akhir minimal [min]. */
        fun force(min: Int, pts: Int, msg: String) { add(pts, msg); if (min > floor) floor = min }
        fun total(): Int = maxOf(score, floor)
    }

    private class Analysis(
        val acc: Acc, val host: String, val scheme: String, val normalized: String
    )

    fun check(raw: String): Report {
        val input = raw.trim()
        if (input.isEmpty()) return Report(Level.AMAN, 0, listOf("Kosong"), "", "", "")

        val hiddenChars = ZERO_WIDTH.containsMatchIn(input)
        val cleaned = ZERO_WIDTH.replace(input, "")

        // 1) Isi QR / teks yang bukan tautan web (wifi:, tel:, javascript:, QRIS, dst.)
        specialPayload(cleaned, hiddenChars)?.let { return it }

        // 2) Ambil SEMUA tautan; yang paling berisiko menentukan hasil.
        val urls = extractAllUrls(cleaned)
        if (urls.isEmpty()) {
            return Report(
                Level.HATI_HATI, THRESHOLD_CAUTION,
                listOf(
                    "Tidak ditemukan tautan web yang dikenali pada isi ini, jadi tidak bisa dinilai. " +
                        "Jangan mengikuti instruksi di dalamnya (transfer, kirim OTP/PIN, pasang aplikasi) bila sumbernya tidak jelas."
                ),
                "", "", cleaned.take(200)
            )
        }
        val candidates = urls
        val analyses = candidates.map { analyze(it) }
        val worst = analyses.maxByOrNull { it.acc.total() } ?: analyses.first()
        val acc = worst.acc

        if (analyses.size > 1) {
            val others = analyses.filter { it !== worst }.map { it.host.ifBlank { "?" } }.distinct().take(4)
            acc.reasons += "Ditemukan ${analyses.size} tautan; yang ditampilkan adalah yang paling berisiko " +
                "(lainnya: ${others.joinToString(", ")})"
        }
        if (hiddenChars) {
            acc.add(25, "Mengandung karakter tak terlihat (zero-width) — dipakai untuk menyamarkan tautan")
        }

        // 3) Isyarat rekayasa sosial pada teks pesan di sekitar tautan.
        val lower = cleaned.lowercase(Locale.US)
        val cues = URGENCY_CUES.filter { lower.contains(it) }
        if (urls.isNotEmpty() && cleaned.length > (urls.firstOrNull()?.length ?: 0) + 12 && cues.isNotEmpty()) {
            acc.add(10, "Teks pesan memakai bahasa mendesak/menggiurkan: ${cues.take(3).joinToString(", ")}")
        }

        return finish(worst, acc, analyses.size)
    }

    private fun finish(a: Analysis, acc: Acc, count: Int): Report {
        if (acc.reasons.isEmpty()) {
            acc.reasons += "Tidak ada sinyal risiko dari aturan lokal"
        }
        val total = acc.total()
        val level = when {
            total >= THRESHOLD_SUSPECT -> Level.MENCURIGAKAN
            total >= THRESHOLD_CAUTION -> Level.HATI_HATI
            else -> Level.AMAN
        }
        if (level == Level.AMAN) {
            acc.reasons += if (acc.trusted) {
                "Domain cocok dengan situs resmi yang dikenal. Tetap pastikan ejaan alamat lengkapnya."
            } else {
                "Domain tidak ada di daftar situs resmi yang dikenal. Pemeriksaan lokal tidak bisa melihat umur " +
                    "domain atau isi halaman: periksa ejaan, jangan masukkan data sensitif bila ragu."
            }
        }
        return Report(level, total, acc.reasons, a.host, a.scheme, a.normalized, acc.trusted && level == Level.AMAN, count)
    }

    /** Ambil URL pertama dari teks bebas (kompatibilitas lama). */
    fun extractUrl(text: String): String? = extractAllUrls(text).firstOrNull()

    /** Ambil semua tautan dari teks bebas (maks. 10, tanpa duplikat). */
    fun extractAllUrls(text: String): List<String> {
        val out = LinkedHashSet<String>()
        for (m in URL_RE.findAll(text)) {
            var v = m.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '>', '"', '\'')
            if (v.isEmpty()) continue
            if (!SCHEME_RE.containsMatchIn(v) && !v.startsWith("www.", true) && !v.contains('/')) {
                // Token tanpa skema seperti "foto.jpg": abaikan bila hanya nama berkas biasa.
                val ext = v.substringAfterLast('.').lowercase(Locale.US).substringBefore(':')
                if (ext in EXT_SAFE_BARE) continue
            }
            out += v
            if (out.size >= 10) break
        }
        return out.toList()
    }

    // ------------------------------------------------------------------
    //  Isi non-web (QR)
    // ------------------------------------------------------------------

    private fun specialPayload(s: String, hidden: Boolean): Report? {
        val t = s.trim()
        val lower = t.lowercase(Locale.US)

        fun rep(level: Level, score: Int, reason: String, scheme: String): Report {
            val reasons = mutableListOf(reason)
            var sc = score
            if (hidden) { sc += 25; reasons += "Mengandung karakter tak terlihat (zero-width)" }
            return Report(level, sc, reasons, "", scheme, t.take(200))
        }

        // QRIS: pembayaran. QR statis sering ditempel/ditimpa penipu di atas QR asli.
        if (t.startsWith("000201") && t.length > 40 && t.all { it.isLetterOrDigit() || it in " .-_*/:+@=&" }) {
            return rep(
                Level.HATI_HATI, 20,
                "Ini kode pembayaran QRIS, bukan tautan web. Pastikan NAMA MERCHANT di aplikasi bayar sesuai, " +
                    "dan QR bukan stiker tempelan di atas QR asli.",
                "qris"
            )
        }

        val m = SPECIAL_SCHEME_RE.find(t) ?: return null
        val sch = m.groupValues[1].lowercase(Locale.US)
        val body = m.groupValues[2]
        if (sch == "http" || sch == "https") return null
        // "host:8080/path" atau "example.com:80" bukan skema.
        if (body.isNotEmpty() && body.first().isDigit() && sch.contains('.')) return null
        if (sch.contains('.') && !sch.startsWith("wifi")) return null

        return when (sch) {
            "javascript", "vbscript", "data", "blob", "file", "jar", "content", "about" ->
                rep(Level.MENCURIGAKAN, 80, "Skema '$sch:' dapat menjalankan kode atau membuka berkas lokal — JANGAN dibuka.", sch)
            "intent", "android-app", "market", "ms-appinstaller", "ms-msdt", "search-ms", "ms-excel", "ms-word" ->
                rep(Level.MENCURIGAKAN, 60,
                    "Skema '$sch:' dapat memicu pemasangan aplikasi / membuka aplikasi lain dari QR. Jangan dibuka bila sumber tidak jelas.", sch)
            "wifi" ->
                rep(Level.HATI_HATI, 25,
                    "QR akan menyambungkan HP ke jaringan WiFi. Jaringan palsu bisa menyadap data; sambungkan hanya ke WiFi yang Anda kenal.", sch)
            "tel", "sms", "smsto", "mms", "mmsto" ->
                rep(Level.HATI_HATI, 25,
                    "QR akan menelepon / mengirim SMS ke nomor tertentu (bisa nomor premium atau penipuan). Periksa nomor sebelum mengirim.", sch)
            "mailto", "matmsg" ->
                rep(Level.HATI_HATI, 18, "QR akan menyiapkan email ke alamat tertentu. Periksa alamat tujuan dan isi pesan.", sch)
            "begin", "mecard" ->
                rep(Level.HATI_HATI, 15, "Kartu kontak: jangan simpan bila tidak dikenal (bisa memuat nomor/tautan penipuan).", sch)
            "otpauth" ->
                rep(Level.HATI_HATI, 25, "Pendaftaran 2FA/OTP: jangan dipindai bila bukan Anda sendiri yang memulai pengaturan akun ini.", sch)
            "bitcoin", "ethereum", "litecoin", "monero", "tron", "solana", "upi", "paytm", "bnb", "dogecoin" ->
                rep(Level.HATI_HATI, 25, "QR pembayaran/kripto: periksa alamat tujuan dengan teliti. Penipu mengganti alamat dompet.", sch)
            "geo", "webcal", "facetime", "skype", "zoommtg", "tg", "whatsapp", "viber", "line", "weixin" ->
                rep(Level.HATI_HATI, 15, "Tautan aplikasi ($sch:) membuka aplikasi lain. Pastikan sumber QR dapat dipercaya.", sch)
            else -> {
                // Skema tak dikenal dengan isi sepele (mis. "Catatan: ...") bukan tautan sama sekali.
                if (body.startsWith("//")) null
                else if (sch.length <= 3 || !body.contains('.')) null
                else rep(Level.HATI_HATI, 20, "Skema tidak umum '$sch:' — jangan dibuka bila sumber tidak jelas.", sch)
            }
        }
    }

    // ------------------------------------------------------------------
    //  Analisis satu URL
    // ------------------------------------------------------------------

    private fun analyze(candidate: String): Analysis {
        val acc = Acc()
        var s = candidate.trim()

        val nfkc = Normalizer.normalize(s, Normalizer.Form.NFKC)
        if (nfkc != s) {
            acc.add(15, "Memakai karakter lebar-penuh / aksara khusus (penyamaran alamat)")
            s = nfkc
        }
        if (s.contains('\\')) {
            acc.add(15, "Memakai garis miring terbalik (\\) — dibaca berbeda oleh browser, teknik pengelabuan")
            s = s.replace('\\', '/')
        }

        var scheme = ""
        var rest = s
        val sm = SCHEME_RE.find(s)
        if (sm != null) {
            scheme = sm.groupValues[1].lowercase(Locale.US)
            rest = s.substring(sm.value.length)
        } else if (s.startsWith("//")) {
            rest = s.substring(2)
        }
        val shownScheme = scheme.ifEmpty { "(tanpa skema)" }

        // Skema
        when {
            scheme == "http" -> acc.add(15, "Menggunakan HTTP (tidak terenkripsi)")
            scheme == "" || scheme == "https" -> {}
            scheme in setOf("ftp", "ftps", "sftp", "smb", "ws", "wss", "gopher", "ldap", "telnet") ->
                acc.add(25, "Skema tidak biasa untuk tautan pesan: $scheme")
            scheme in setOf("tg", "whatsapp", "viber", "line", "weixin", "skype") ->
                acc.add(12, "Tautan aplikasi ($scheme://) — periksa sumbernya")
            scheme in setOf("intent", "market", "android-app", "javascript", "data", "file") ->
                acc.force(60, 60, "Skema berbahaya: $scheme")
            else -> acc.add(25, "Skema tidak biasa: $scheme")
        }

        // Pecah authority / path
        val authEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (authEnd < 0) rest else rest.substring(0, authEnd)
        val tail = if (authEnd < 0) "" else rest.substring(authEnd)

        val at = authority.lastIndexOf('@')
        val userinfo = if (at >= 0) authority.substring(0, at) else ""
        var hostport = if (at >= 0) authority.substring(at + 1) else authority
        if (at >= 0) {
            if (userinfo.contains('.') || userinfo.contains("http", true) || userinfo.contains(':')) {
                acc.force(
                    50, 45,
                    "Ada '@' di alamat: teks sebelum '@' ('${userinfo.take(40)}') diabaikan browser dan dipakai " +
                        "untuk menampilkan domain palsu"
                )
            } else {
                acc.force(40, 35, "Mengandung '@' di bagian alamat (teknik penyamaran phishing klasik)")
            }
        }

        var port = -1
        var host: String
        if (hostport.startsWith("[")) {
            val end = hostport.indexOf(']')
            host = if (end > 0) hostport.substring(1, end) else hostport.removePrefix("[")
            val after = if (end > 0) hostport.substring(end + 1) else ""
            if (after.startsWith(":")) port = after.drop(1).toIntOrNull() ?: -1
        } else {
            val colon = hostport.lastIndexOf(':')
            if (colon >= 0 && hostport.substring(colon + 1).all { it.isDigit() }) {
                port = hostport.substring(colon + 1).toIntOrNull() ?: -1
                hostport = hostport.substring(0, colon)
            }
            host = hostport
        }
        host = host.lowercase(Locale.US)
        if (host.endsWith(".")) {
            host = host.trimEnd('.')
            acc.add(5, "Host diakhiri titik")
        }

        val normalized = if (scheme.isEmpty()) "https://$rest" else s

        if (host.isBlank()) {
            acc.add(35, "Host tidak ditemukan / format URL tidak valid")
            return Analysis(acc, "", shownScheme, normalized)
        }

        if (host.contains('%')) {
            acc.add(25, "Host mengandung persen-encoding (penyamaran alamat)")
            host = pctDecode(host).lowercase(Locale.US)
        }
        if (host.any { it.isWhitespace() || it in "<>\"'`{}|^" }) {
            acc.add(20, "Host mengandung karakter tidak lazim")
        }

        // Port
        if (port != -1 && port != 80 && port != 443) {
            acc.add(10, "Port non-standar: $port")
        }

        // ---- IP mentah / samar ----
        val ipKind = ipKind(host)
        if (ipKind != null) {
            when (ipKind) {
                "private" -> acc.add(
                    12, "Alamat IP jaringan lokal (bukan situs publik). QR/tautan ke perangkat di jaringan Anda tidak lazim — " +
                        "pastikan Anda memang memintanya."
                )
                "plain" -> acc.force(40, 35, "Host berupa alamat IP mentah (situs resmi hampir tidak pernah memakai ini)")
                else -> acc.force(50, 50, "Alamat IP disamarkan (format $ipKind) — teknik penghindar filter")
            }
            analyzePath(tail, acc, host, ugc = false, officialHost = false)
            lengthChecks(s, acc)
            return Analysis(acc, host, shownScheme, normalized)
        }

        // ---- IDN / homograf ----
        val nonAscii = host.any { it.code > 127 }
        val punycode = host.split('.').any { it.startsWith("xn--") }
        var unicodeHost = host
        if (punycode) {
            unicodeHost = try { IDN.toUnicode(host) } catch (_: Exception) { host }
        }
        var asciiHost = host
        if (nonAscii) {
            asciiHost = try { IDN.toASCII(host, IDN.ALLOW_UNASSIGNED) } catch (_: Exception) { host }
        }
        val skeleton = skeleton(unicodeHost)
        if (nonAscii || punycode) {
            acc.add(30, "Domain IDN/Unicode (huruf bisa meniru huruf Latin): $unicodeHost")
            val scripts = unicodeHost.filter { it.isLetter() }.map { Character.UnicodeScript.of(it.code) }.toSet()
            if (scripts.size > 1) acc.add(25, "Campuran beberapa aksara dalam satu domain (ciri homograf)")
            if (skeleton != unicodeHost.lowercase(Locale.US) || scripts.any { it != Character.UnicodeScript.LATIN }) {
                // dicek lagi pada deteksi merek memakai skeleton
            }
        }

        val labels = asciiHost.split('.').filter { it.isNotEmpty() }
        val tld = labels.lastOrNull().orEmpty()
        if (tld in FILE_AS_TLD && tail.isEmpty()) {
            acc.force(55, 50, "Ini nama berkas berbahaya (.$tld), bukan situs web — jangan dibuka/dipasang bila diterima dari chat")
        }

        // ---- Public suffix & domain terdaftar ----
        val sufLen = suffixLength(labels)
        val regLabel = if (labels.size > sufLen) labels[labels.size - sufLen - 1] else ""
        val registrable = if (labels.size > sufLen) labels.takeLast(sufLen + 1).joinToString(".") else asciiHost
        val subDepth = (labels.size - sufLen - 1).coerceAtLeast(0)

        val shortener = matchSuffix(asciiHost, SHORTENERS)
        val ugcSuffix = longestSuffix(asciiHost, UGC_HOSTS)
        val messaging = matchSuffix(asciiHost, MESSAGING_HOSTS)

        // ---- Merek: siapa yang ditiru? ----
        // Bagian "buatan pengguna" dari host (tanpa nama platform gratisan).
        val userHost = when {
            ugcSuffix != null && asciiHost.length > ugcSuffix.length -> asciiHost.removeSuffix(".$ugcSuffix")
            ugcSuffix != null -> ""
            else -> asciiHost
        }
        val coreHost = stripSuffix(userHost, labels, sufLen, ugcSuffix != null)
        val officialBrand = BRANDS.firstOrNull { isOfficial(asciiHost, it) }
        val spoof = if (userHost.isNotEmpty()) findSpoof(asciiHost, coreHost, skeletonOf(coreHost, skeleton)) else null

        if (spoof != null) {
            val (brand, typo) = spoof
            val official = brand.official.take(3).joinToString(", ")
            acc.force(
                60, 55,
                (if (typo) "Ejaan mirip merek " else "Mencatut nama merek ") + brand.name +
                    " tetapi BUKAN domain resminya (resmi: $official${if (brand.official.size > 3) ", ..." else ""})"
            )
        }
        if (officialBrand != null && ugcSuffix == null && shortener == null) acc.trusted = true

        // ---- Penyamaran pola "domain.com.penipu.xyz" ----
        val fakeSuffixLabel = labels.dropLast(sufLen + 1).any { it in setOf("com", "net", "org", "gov", "edu", "co", "go", "or", "ac") }
        if (fakeSuffixLabel && officialBrand == null) {
            acc.force(40, 25, "Memakai pola 'nama.com.domainlain' untuk meniru domain resmi")
        }
        if (labels.any { it == "www" || it == "https" || it == "http" } && labels.indexOfFirst { it == "https" || it == "http" } >= 0) {
            acc.add(20, "Kata 'http/https' dipakai sebagai bagian nama domain (penyamaran)")
        } else if (regLabel.startsWith("www") && regLabel.length > 3 && !regLabel.startsWith("www.")) {
            acc.add(15, "Nama domain diawali 'www' tanpa titik (penyamaran)")
        }

        // ---- Shortener ----
        if (shortener != null) {
            acc.force(
                20, 25,
                "Pemendek tautan ($shortener): tujuan asli tersembunyi. Jangan buka langsung; gunakan pratinjau/ekspansi dulu"
            )
        }

        // ---- Layanan gratisan / konten pengguna ----
        if (ugcSuffix != null) {
            val isForm = asciiHost.startsWith("docs.google.com") || asciiHost.endsWith("forms.gle") ||
                asciiHost.endsWith("jotform.com") || asciiHost.endsWith("typeform.com") || asciiHost.endsWith("forms.office.com")
            if (isForm) {
                acc.add(
                    20, "Formulir online ($ugcSuffix) sangat sering dipakai untuk mencuri data. " +
                        "Instansi/bank resmi tidak meminta PIN/OTP/password lewat formulir."
                )
            } else {
                acc.add(
                    14, "Layanan hosting/konten buatan pengguna ($ugcSuffix): tautannya terlihat 'bersih' " +
                        "tetapi isi halaman bisa dibuat siapa saja"
                )
            }
        }

        // ---- Messaging deep link ----
        if (messaging != null) {
            acc.add(12, "Tautan ajakan chat ($messaging): penipuan sering memindahkan korban ke WhatsApp/Telegram — " +
                "jangan berikan OTP/PIN/data pribadi")
        }

        // ---- TLD ----
        when {
            tld in TOP_TLDS ->
                acc.force(40, 30, "TLD .$tld termasuk yang PALING sering dipakai untuk phishing (laporan Interisle 2025)")
            tld in HIGH_TLDS && tld !in setOf("om", "cm", "con", "cim", "vom") ->
                acc.add(20, "TLD .$tld sangat sering dipakai untuk phishing/penipuan")
            tld in setOf("om", "cm", "con", "cim", "vom") ->
                acc.add(22, "TLD .$tld kemungkinan salah ketik/penyamaran dari .com")
            tld in MEDIUM_TLDS && officialBrand == null && ugcSuffix == null && shortener == null ->
                acc.add(8, "TLD .$tld cukup sering disalahgunakan")
        }

        // ---- Struktur domain ----
        if (subDepth >= 3) acc.add(12, "Subdomain terlalu dalam ($subDepth level) — untuk menyembunyikan domain asli")
        val hyphens = regLabel.count { it == '-' }
        if (hyphens >= 2) acc.add(10, "Banyak tanda hubung pada nama domain ($hyphens)")
        else if (hyphens == 1 && spoof == null && officialBrand == null) acc.add(3, "Tanda hubung pada nama domain")
        if (asciiHost.length > 45) acc.add(8, "Nama host sangat panjang (${asciiHost.length} karakter)")
        if (regLabel.length >= 10 && vowelRatio(regLabel) < 0.2 && regLabel.none { it == '-' }) {
            acc.add(10, "Nama domain tampak acak/tidak bermakna (ciri domain buatan mesin)")
        }
        if (regLabel.length >= 6 && regLabel.count { it.isDigit() } * 2 >= regLabel.length && officialBrand == null) {
            acc.add(8, "Nama domain didominasi angka")
        }

        // ---- Kata sensitif / umpan di host ----
        if (officialBrand == null || ugcSuffix != null) {
            val hostTokens = tokens(coreHost)
            val credHits = hostTokens.filter { it in CRED_WORDS }.distinct()
            val lureHits = hostTokens.filter { it in LURE_WORDS }.distinct()
            val n = credHits.size + lureHits.size
            if (n > 0) {
                acc.add(
                    (8 * n).coerceAtMost(24),
                    "Nama domain memuat kata pancingan: ${(credHits + lureHits).take(4).joinToString(", ")}"
                )
                val risky = tld in HIGH_TLDS || tld in MEDIUM_TLDS || ugcSuffix != null || hyphens >= 1 || subDepth >= 2
                if (risky) acc.add(15, "Kata pancingan + domain/hosting berisiko (kombinasi khas phishing)")
                if (spoof != null) acc.add(10, "Kata pancingan dipadukan dengan nama merek")
            }
            // Judi online
            val gamb = hostTokens.firstOrNull { t -> GAMBLING_PREFIX.any { t.startsWith(it) } }
            if (gamb != null && !asciiHost.endsWith(".go.id") && !asciiHost.endsWith(".ac.id")) {
                acc.force(
                    45, 45,
                    "Pola domain judi online ('$gamb') — situs ilegal yang kerap jadi pintu penipuan, pencurian data, dan malware"
                )
            }
        }

        analyzePath(tail, acc, asciiHost, ugc = ugcSuffix != null, officialHost = officialBrand != null)
        lengthChecks(s, acc)

        if (acc.total() < THRESHOLD_CAUTION && officialBrand == null && ugcSuffix == null && shortener == null) {
            // domain tak dikenal tanpa sinyal lain: catatan ringan saja (tetap level AMAN)
        }
        return Analysis(acc, asciiHost, shownScheme, normalized)
    }

    // ------------------------------------------------------------------
    //  Path / query
    // ------------------------------------------------------------------

    private fun analyzePath(tail: String, acc: Acc, host: String, ugc: Boolean, officialHost: Boolean) {
        if (tail.isEmpty()) return
        val qIdx = tail.indexOf('?').let { if (it < 0) tail.length else it }
        val hIdx = tail.indexOf('#').let { if (it < 0) tail.length else it }
        val pathEnd = minOf(qIdx, hIdx)
        val rawPath = tail.substring(0, pathEnd)
        val rawQuery = if (qIdx < tail.length) tail.substring(qIdx + 1, if (hIdx > qIdx) hIdx else tail.length) else ""
        val fragment = if (hIdx < tail.length) tail.substring(hIdx + 1) else ""

        var dPath = pctDecode(rawPath)
        var dQuery = pctDecode(rawQuery)
        // double-encoding
        if (rawPath.contains("%25") || rawQuery.contains("%25")) {
            acc.add(15, "Persen-encoding ganda (%25) — teknik menyembunyikan isi tautan")
            dPath = pctDecode(dPath)
            dQuery = pctDecode(dQuery)
        }
        val encodedLen = (rawPath + rawQuery).count { it == '%' }
        if (encodedLen > 12 && encodedLen * 3 > (rawPath + rawQuery).length * 0.3) {
            acc.add(8, "Sangat banyak karakter ter-encode ($encodedLen)")
        }
        if (rawPath.contains("%00") || rawQuery.contains("%00", true)) acc.force(40, 30, "Terdeteksi null-byte / encoding berbahaya")

        val lowerPath = dPath.lowercase(Locale.US)
        val lowerQuery = dQuery.lowercase(Locale.US)
        val whole = "$lowerPath?$lowerQuery"

        // ---- Ekstensi berkas ----
        val segment = lowerPath.trimEnd('/').substringAfterLast('/')
        val ext = if (segment.contains('.')) segment.substringAfterLast('.') else ""
        val queryExt = Regex("\\.([a-z0-9]{2,5})(?=$|[&#/?\"' ])").findAll(lowerQuery).map { it.groupValues[1] }.toList()
        val exts = (listOf(ext) + queryExt).filter { it.isNotEmpty() }
        val highExt = exts.firstOrNull { it in EXT_HIGH }
        when {
            highExt != null && (highExt == "apk" || highExt == "xapk" || highExt == "apks" || highExt == "apk1") ->
                acc.force(
                    60, 50,
                    "Tautan mengunduh aplikasi Android (.$highExt) di luar Play Store — modus utama penipuan " +
                        "(undangan / resi / BPJS / tilang / bansos). JANGAN dipasang."
                )
            highExt != null ->
                acc.force(55, 45, "Tautan mengunduh berkas berbahaya (.$highExt)")
            exts.any { it in EXT_MEDIUM } ->
                acc.add(20, "Tautan mengunduh arsip (.${exts.first { it in EXT_MEDIUM }}) — sering berisi malware")
            exts.any { it in EXT_PAGE } && (ugc || !officialHost) ->
                acc.add(12, "Tautan ke berkas halaman (.${exts.first { it in EXT_PAGE }}) — kit phishing sering berupa berkas HTML/SVG")
        }
        if (Regex("\\.[a-z0-9]{2,4}\\.(apk|exe|scr|bat|js|vbs|hta|lnk|jar)$").containsMatchIn(segment)) {
            acc.force(55, 20, "Ekstensi ganda pada nama berkas (mis. foto.jpg.apk) — penyamaran berkas berbahaya")
        }

        // ---- Kata di path & query ----
        val pTokens = tokens(whole)
        val credHits = pTokens.filter { it in CRED_WORDS }.distinct()
        val lureHits = pTokens.filter { it in LURE_WORDS }.distinct()
        val hitCount = credHits.size + lureHits.size
        if (hitCount > 0 && !officialHost) {
            acc.add(
                (6 * hitCount).coerceAtMost(18),
                "Path/query memuat kata sensitif/umpan: ${(credHits + lureHits).take(4).joinToString(", ")}"
            )
        } else if (hitCount > 0 && officialHost && ugc) {
            acc.add(6, "Path memuat kata sensitif: ${(credHits + lureHits).take(3).joinToString(", ")}")
        }
        val gambPath = pTokens.firstOrNull { t -> GAMBLING_PREFIX.any { t.startsWith(it) } }
        if (gambPath != null && !host.endsWith(".go.id")) {
            acc.force(40, 35, "Path memuat kata judi online ('$gambPath')")
        }

        // ---- Parameter pengalihan & kit phishing ----
        var redirectFound = false
        var emailPrefilled = false
        for (pair in dQuery.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val name = (if (eq >= 0) pair.substring(0, eq) else pair).lowercase(Locale.US)
            val value = if (eq >= 0) pair.substring(eq + 1) else ""
            val vl = value.lowercase(Locale.US)
            if (name in REDIRECT_PARAMS && (vl.startsWith("http") || vl.startsWith("//") || vl.contains("://"))) redirectFound = true
            if (!redirectFound && (vl.startsWith("http://") || vl.startsWith("https://"))) redirectFound = true
            if (name in EMAIL_PARAMS && Regex("^[^@\\s]+@[^@\\s]+\\.[a-z]{2,}$").matches(vl)) emailPrefilled = true
            if (value.length >= 60 && Regex("^[A-Za-z0-9+/=_\\-]+$").matches(value)) {
                acc.add(6, "Parameter panjang berisi data ter-encode (mis. base64) — sering membawa alamat korban/tujuan tersembunyi")
            }
        }
        if (redirectFound) {
            acc.add(
                if (officialHost) 30 else 25,
                "Parameter pengalihan ke URL lain di dalam tautan (open-redirect: domain terlihat resmi, tujuan akhir lain)"
            )
        }
        if (emailPrefilled || fragment.contains('@')) {
            acc.add(20, "Alamat email sudah terisi di tautan (pola kit phishing yang menargetkan korban tertentu)")
        }
        if (Regex("(?i)(https?:)?//[^/]+\\.[a-z]{2,}").containsMatchIn(lowerPath.removePrefix("/"))) {
            acc.add(20, "Ada URL lain di dalam path (pola penyamaran / pengalihan)")
        }

        if ((lowerPath.contains("/wp-content/") || lowerPath.contains("/wp-includes/") || lowerPath.contains("/wp-admin/") ||
                lowerPath.contains("/wp-json/")) && (hitCount > 0 || ext in setOf("php", "html", "htm", "zip", "js", "apk"))
        ) {
            acc.add(15, "Jalur WordPress dengan berkas/kata mencurigakan (kit phishing sering ditanam di situs yang diretas)")
        }
        if ((lowerPath.contains("/cgi-bin/") || lowerPath.contains("/.well-known/") || lowerPath.contains("/~")) && hitCount > 0) {
            acc.add(8, "Jalur khas kit phishing di hosting bersama")
        }
        if (lowerPath.length > 3 && Regex("/(index|login|signin|verify|secure|update|confirm|auth)\\.(php|html|htm|asp|aspx|jsp)$").containsMatchIn(lowerPath) && !officialHost) {
            acc.add(10, "Berkas halaman login/verifikasi statis (${segment}) — khas halaman phishing")
        }
    }

    private fun lengthChecks(s: String, acc: Acc) {
        if (s.length > 300) acc.add(8, "URL sangat panjang (${s.length} karakter)")
        else if (s.length > 150) acc.add(4, "URL cukup panjang (${s.length} karakter)")
    }

    // ------------------------------------------------------------------
    //  Merek / typosquat
    // ------------------------------------------------------------------

    /** Kembalikan (merek, apakahTypo) bila host mencatut merek yang bukan miliknya. */
    private fun findSpoof(fullHost: String, coreHost: String, skeleton: String): Pair<Brand, Boolean>? {
        val sources = LinkedHashSet<String>()
        sources += coreHost
        sources += skeleton
        sources += leet(coreHost, 'l')
        sources += leet(coreHost, 'i')
        sources += leet(skeleton, 'l')
        var typoHit: Pair<Brand, Boolean>? = null
        for (src in sources) {
            val toks = tokens(src)
            val joined = src.filter { it.isLetterOrDigit() }
            for (br in BRANDS) {
                // Host memang milik merek ini (domain resmi / subdomainnya)? lewati
                if (isOfficial(fullHost, br)) continue
                for (kw in br.keywords) {
                    val exact = matchKeyword(kw, toks, joined)
                    if (exact) return br to false
                    if (typoHit == null && kw.length >= 6) {
                        for (t in toks) {
                            if (t == kw) continue
                            val dl = kotlin.math.abs(t.length - kw.length)
                            if (dl <= 1 && levenshtein(t, kw) <= (if (kw.length >= 9) 2 else 1)) {
                                typoHit = br to true
                            }
                        }
                    }
                }
            }
        }
        return typoHit
    }

    private fun matchKeyword(kw: String, toks: List<String>, joined: String): Boolean {
        if (kw.length <= 5) {
            for (t in toks) {
                if (t == kw) return true
                if (t.startsWith(kw) && t.substring(kw.length) in AFFIX) return true
                if (t.endsWith(kw) && t.substring(0, t.length - kw.length) in AFFIX) return true
            }
            return false
        }
        if (toks.any { it.contains(kw) }) return true
        // kata dipisah tanda hubung (pos-indonesia, bank-mandiri) -> cocokkan pada gabungan
        return kw.length >= 7 && joined.contains(kw)
    }

    private fun isOfficial(host: String, br: Brand): Boolean =
        br.official.any { host == it || host.endsWith(".$it") }

    // ------------------------------------------------------------------
    //  Util
    // ------------------------------------------------------------------

    private fun tokens(s: String): List<String> =
        s.lowercase(Locale.US).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    private fun matchSuffix(host: String, set: Set<String>): String? =
        set.firstOrNull { host == it || host.endsWith(".$it") }

    private fun longestSuffix(host: String, set: Set<String>): String? =
        set.filter { host == it || host.endsWith(".$it") }.maxByOrNull { it.length }

    private fun suffixLength(labels: List<String>): Int {
        if (labels.size >= 2) {
            val two = labels[labels.size - 2] + "." + labels.last()
            if (two in MULTI_SUFFIX) return 2
            if (labels.last().length == 2 && labels[labels.size - 2] in CC_SECOND) return 2
        }
        return 1
    }

    /** Buang akhiran publik (dan nama platform gratisan) agar hanya bagian bermakna yang dicek. */
    private fun stripSuffix(userHost: String, labels: List<String>, sufLen: Int, ugc: Boolean): String {
        if (userHost.isEmpty()) return ""
        if (ugc) return userHost // sudah tanpa nama platform; TLD bukan bagian dari userHost
        val ls = userHost.split('.').filter { it.isNotEmpty() }
        return if (ls.size > sufLen) ls.dropLast(sufLen).joinToString(".") else ls.joinToString(".")
    }

    private fun pctDecode(s: String): String = try {
        URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    } catch (_: Exception) {
        s
    }

    private fun vowelRatio(s: String): Double {
        val letters = s.filter { it.isLetter() }
        if (letters.isEmpty()) return 1.0
        return letters.count { it in "aeiouy" }.toDouble() / letters.length
    }

    private fun leet(s: String, one: Char): String {
        val sb = StringBuilder()
        for (c in s) {
            sb.append(
                when (c) {
                    '0' -> 'o'; '3' -> 'e'; '4' -> 'a'; '5' -> 's'; '7' -> 't'; '$' -> 's'; '@' -> 'a'; '1' -> one
                    else -> c
                }
            )
        }
        return sb.toString().replace("rn", "m").replace("vv", "w")
    }

    private val CONFUSABLES: Map<Char, Char> = mapOf(
        'а' to 'a', 'е' to 'e', 'о' to 'o', 'р' to 'p', 'с' to 'c', 'у' to 'y', 'х' to 'x', 'і' to 'i', 'ј' to 'j',
        'ӏ' to 'l', 'ԁ' to 'd', 'ɡ' to 'g', 'ο' to 'o', 'α' to 'a', 'ν' to 'v', 'ρ' to 'p', 'τ' to 't', 'ε' to 'e',
        'ι' to 'i', 'κ' to 'k', 'υ' to 'u', 'β' to 'b', 'η' to 'n', 'ɑ' to 'a', 'ı' to 'i', 'ⅼ' to 'l', 'ⅰ' to 'i',
        'ɩ' to 'i', 'ƅ' to 'b', 'ɗ' to 'd', 'ʏ' to 'y', 'ᴏ' to 'o', 'ᴀ' to 'a', 'ʟ' to 'l', 'ꓲ' to 'l', 'ǀ' to 'l',
        'в' to 'b', 'к' to 'k', 'м' to 'm', 'н' to 'h', 'т' to 't', 'ь' to 'b', 'ѕ' to 's', 'ԛ' to 'q', 'ԝ' to 'w'
    )

    /** Ubah huruf kembaran (Cyrillic/Greek/dll.) menjadi huruf Latin padanannya. */
    private fun skeleton(s: String): String {
        val lower = s.lowercase(Locale.US)
        val sb = StringBuilder()
        for (c in lower) sb.append(CONFUSABLES[c] ?: c)
        return Normalizer.normalize(sb.toString(), Normalizer.Form.NFD).filter { it.code < 0x300 || it.code > 0x36F }
    }

    private fun skeletonOf(coreHost: String, fullSkeleton: String): String {
        // skeleton dihitung dari host Unicode asli; untuk host ASCII cukup coreHost itu sendiri.
        return if (fullSkeleton.contains(coreHost)) coreHost else skeleton(coreHost)
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    /** null = bukan IP; "plain" / "private" / nama format samar (desimal, heksa, oktal, IPv6, ...). */
    private fun ipKind(host: String): String? {
        if (host.contains(':')) return "IPv6"
        val m = IPV4_RE.find(host)
        if (m != null) {
            val parts = m.groupValues.drop(1)
            if (parts.any { it.length > 1 && it.startsWith("0") }) return "oktal/nol-depan"
            val n = parts.map { it.toInt() }
            if (n.any { it > 255 }) return "plain"
            val a = n[0]; val bb = n[1]
            val priv = a == 10 || a == 127 || (a == 192 && bb == 168) || (a == 172 && bb in 16..31) || (a == 169 && bb == 254)
            return if (priv) "private" else "plain"
        }
        if (host.all { it.isDigit() } && host.length in 5..10) return "desimal"
        if (host.startsWith("0x") && host.drop(2).all { it.isDigit() || it in 'a'..'f' } && host.length > 3) return "heksadesimal"
        val parts = host.split('.')
        if (parts.size in 2..4 && parts.all { p -> p.isNotEmpty() && (p.all { it.isDigit() } || (p.startsWith("0x") && p.length > 2)) }) {
            return "campuran/heksa"
        }
        return null
    }
}   