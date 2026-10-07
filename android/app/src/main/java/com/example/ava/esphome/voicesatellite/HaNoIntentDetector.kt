package com.example.ava.esphome.voicesatellite

/**
 * Exact match against Home Assistant default-agent `responses.errors.no_intent`
 * (one fixed phrase per language pack). Not Ava-local copy.
 *
 * Kept tight on purpose: a bare "sorry" must never trigger the teaching tip.
 * Device-miss replies (`no_entity`, `no_device_class`, …) carry slots and live in
 * [HaAssistMissDetector], matched against the official templates.
 */
object HaNoIntentDetector {

    /**
     * Official phrases from home-assistant/intents `sentences/<lang>/_common.yaml`
     * plus conversation/default_agent.py `_DEFAULT_ERROR_TEXT`.
     */
    private val NO_INTENT_PHRASES: Set<String> = setOf(
        "Jammer, ek verstaan nie",
        "عذرًا، لم أفهم ذلك",
        "Съжалявам, не можах да разбера това",
        "দুঃখিত আমি বুঝতে পারিনি",
        "Ho sento, no entenc això",
        "Promiňte, ale nerozumím",
        "Undskyld, det forstod jeg ikke",
        "Entschuldigung, das habe ich nicht verstanden",
        "Tschuldigung, das han i nid verstande",
        "Συγγνώμη, δεν μπόρεσα να το καταλάβω αυτό",
        "Sorry, I couldn't understand that",
        "Lo siento, no he entendido",
        "Vabandust, ma ei saanud aru",
        "Barkatu, ez dizut ulertu",
        "ببخشید نتونستم بفهمم",
        "Pahoittelut, en ymmärtänyt",
        "Désolé, je n'ai pas compris",
        "Síntoo, non che entendín",
        "માફ કરશો, મને તે સમજાયું નહીં",
        "מצטער, לא הצלחתי להבין את הבקשה",
        "क्षमा करें, मुझे यह समझ में नहीं आया",
        "Nažalost, ne razumijem zahtjev",
        "Sajnálom, ezt nem értettem.",
        "Maaf, Saya tidak mengerti",
        "Fyrirgefðu en ég náði þessu ekki",
        "Mi dispiace, non ho capito",
        "უკაცრავად, ვერ გავიგე",
        "죄송합니다, 이해하지 못했습니다",
        "Drog yw genev, ny yllis vy konvedhes henna",
        "Et deet mer Leed, ech hunn dat net verstanen",
        "Atsiprašau, užklausa nebuvo suprasta",
        "Atvainojiet, es nevarēju to saprast",
        "ക്ഷമിക്കണം, എനിക്ക് അത് മനസ്സിലായില്ല",
        "Уучлаарай, Таныг ойлгосонгүй",
        "माफ करा, मला समजले नाही",
        "Maaf, saya tidak faham arahan anda",
        "Jeg skjønte dessverre ikke det",
        "माफ गर्नुहोस, मैले यो कुरा बुज्न सकिन",
        "Sorry, ik snap het niet",
        "Wybacz, niestety nie mogę tego zrozumieć",
        "Desculpe, não percebi o pedido.",
        "Desculpe, não consegui entender seu pedido",
        "Îmi pare rău, nu am înțeles cererea. Poți, te rog, să repeți?",
        "Обращение не распознано",
        "Prepáč, tomuto povelu nerozumiem",
        "Oprosti, tega nisem razumel",
        "Извини, нисам то могао да разумем",
        "Nažalost, nerazumem",
        "Ursäkta, jag förstår inte",
        "Samahani, sikuweza kuelewa hilo",
        "క్షమించండి, నకు అర్థం అవ్వలేదు",
        "ขอโทษด้วย ฉันไม่เข้าใจสิ่งที่คุณต้องการ",
        "Üzgünüm, bunu anlayamadım.",
        "Вибачте, я цього не розумію",
        "مجھے سمجھ میں نہیں آیا",
        "Xin lỗi, tôi không hiểu ý bạn",
        "抱歉，我无法理解您的意思，你可以尝试换个说法",
        "對唔住，我聽唔明白",
        "抱歉，我沒聽懂",
    )

    fun matches(speech: String): Boolean {
        val text = speech.trim()
        // Stock replies are short; longer LLM text must not match.
        if (text.isEmpty() || text.length > 96) return false
        return text in NO_INTENT_PHRASES
    }
}
