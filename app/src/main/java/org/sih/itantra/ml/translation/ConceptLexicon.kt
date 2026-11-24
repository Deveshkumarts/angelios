package org.sih.itantra.ml.translation

import org.sih.itantra.domain.model.Language
import java.text.Normalizer
import java.util.Locale

/**
 * Offline concept-based translator for Indic-source text (any of the 10 Indic languages -> English or
 * another Indic language). Used when neither a curated phrase nor the ML Kit neural model can answer.
 *
 * Every emergency-domain word/phrase is a "concept" with one surface form set per language. Source
 * tokens are mapped to concepts (exact, longest-span first, then inflection-tolerant prefix match) and
 * re-rendered in the target language. All ten Indic languages are verb-final (SOV), so Indic->Indic keeps
 * source order; Indic->English is re-ordered into SVO clauses.
 *
 * The lexicon is deliberately conservative: it only answers when EVERY word of the input is known, so it
 * can never emit half-translated, mixed-script output.
 */
object ConceptLexicon {

    // Column order: id | type | en | tam | hin | tel | kan | mal | ben | mar | guj | pan | ory
    // Types: P person/pronoun subject, O oblique pronoun, N noun, V verb (imperative), C conjunctive verb
    //        ("come and"), S state predicate, E experiencer verb (need), A adverb, D discourse (please),
    //        Q question word, F function word (dropped). "~" before an English form = no copula inserted.
    private const val DATA = """
i|P|I|நான்|मैं|నేను|ನಾನು|ഞാൻ|আমি|मी|હું|ਮੈਂ|ମୁଁ
we|P|we|நாங்கள்/நாங்க/நாம்/நாம|हम|మేము/మనం|ನಾವು|ഞങ്ങൾ/നമ്മൾ|আমরা|आम्ही/आपण|અમે/આપણે|ਅਸੀਂ|ଆମେ
you|P|you|நீங்கள்/நீங்க/நீ|आप/तुम|మీరు/నువ్వు|ನೀವು/ನೀನು|നിങ്ങൾ/നീ|আপনি/তুমি|तुम्ही/तू|તમે/તું|ਤੁਸੀਂ/ਤੂੰ|ଆପଣ/ତୁମେ
he|P|he|அவர்/அவன்/அவள்|वह|అతను/ఆమె/వాడు|ಅವನು/ಅವಳು|അവൻ/അവൾ|সে|तो/ती|તે|ਉਹ|ସେ
they|P|they|அவர்கள்/அவங்க|वे/वो|వారు|ಅವರು|അവർ|তারা|ते|તેઓ|ਓਹ|ସେମାନେ
us|O|us|எங்களுக்கு/எங்களை/எங்கள்|हमें/हमारी/हमारे/हमारा|మాకు/మమ్మల్ని/మా|ನಮಗೆ/ನಮ್ಮನ್ನು/ನಮ್ಮ|ഞങ്ങൾക്ക്/ഞങ്ങളെ/ഞങ്ങളുടെ|আমাদের/আমাদেরকে|आम्हाला/आमच्या|અમને/અમારી/અમારા|ਸਾਨੂੰ/ਸਾਡੀ/ਸਾਡੇ|ଆମକୁ/ଆମର
me|O|me|எனக்கு/என்னை|मुझे/मेरी/मेरे/मेरा|నాకు/నన్ను|ನನಗೆ/ನನ್ನನ್ನು|എനിക്ക്/എന്നെ|আমাকে/আমার|मला/माझी|મને/મારી/મારો|ਮੈਨੂੰ/ਮੇਰੀ/ਮੇਰਾ|ମୋତେ/ମୋର
person|P|one person|ஒருவர்/ஒரு நபர்|एक व्यक्ति/एक आदमी|ఒకరు/ఒక వ్యక్తి|ಒಬ್ಬರು/ಒಬ್ಬರಿಗೆ/ಒಬ್ಬ ವ್ಯಕ್ತಿ|ഒരാൾ|একজন|एक व्यक्ती/एकजण|એક વ્યક્તિ|ਇੱਕ ਵਿਅਕਤੀ|ଜଣେ
people|P|people|மக்கள்/ஆட்கள்|लोग|ప్రజలు/జనం|ಜನರು/ಜನ|ആളുകൾ|মানুষ/লোকজন|लोक/माणसे|લોકો|ਲੋਕ|ଲୋକେ
child|N|child|குழந்தை/குழந்தைகள்|बच्चा/बच्चे|పిల్లవాడు/పిల్లలు|ಮಗು/ಮಕ್ಕಳು|കുട്ടി/കുട്ടികൾ|শিশু/বাচ্চা|मूल/मुलं|બાળક/બાળકો|ਬੱਚਾ/ਬੱਚੇ|ଶିଶୁ/ପିଲା
elderly|N|elderly people|முதியவர்கள்/முதியவர்|बुजुर्ग|వృద్ధులు|ವೃದ್ಧರು|വൃദ്ധർ|বৃদ্ধ|वृद्ध|વૃદ્ધો|ਬਜ਼ੁਰਗ|ବୃଦ୍ଧ
doctor|N|doctor|மருத்துவர்/டாக்டர்|डॉक्टर|డాక్టర్/వైద్యుడు|ವೈದ್ಯರು/ಡಾಕ್ಟರ್|ഡോക്ടർ|ডাক্তার|डॉक्टर|ડૉક્ટર|ਡਾਕਟਰ|ଡାକ୍ତର
rescue team|N|rescue team|மீட்புக் குழு/மீட்பு குழு|बचाव दल|రెస్క్యూ బృందం|ರಕ್ಷಣಾ ತಂಡ|രക്ഷാ സംഘം|উদ্ধারকারী দল|बचाव पथक|બચાવ ટીમ|ਬਚਾਅ ਟੀਮ|ଉଦ୍ଧାର ଦଳ
ambulance|N|ambulance|ஆம்புலன்ஸ்|एम्बुलेंस|అంబులెన్స్|ಆಂಬ್ಯುಲೆನ್ಸ್|ആംബുലൻസ്|অ্যাম্বুলেন্স|रुग्णवाहिका/अॅम्ब्युलन्स|એમ્બ્યુલન્સ|ਐਂਬੂਲੈਂਸ|ଆମ୍ବୁଲାନ୍ସ
hospital|N|hospital|மருத்துவமனை|अस्पताल|ఆసుపత్రి|ಆಸ್ಪತ್ರೆ|ആശുപത്രി|হাসপাতাল|रुग्णालय/हॉस्पिटल|હોસ્પિટલ|ਹਸਪਤਾਲ|ଡାକ୍ତରଖାନା
water|N|water|தண்ணீர்/நீர்|पानी|నీళ్ళు/నీరు|ನೀರು|വെള്ളം|জল/পানি|पाणी|પાણી|ਪਾਣੀ|ପାଣି
food|N|food|உணவு/சாப்பாடு|खाना/भोजन|ఆహారం|ಆಹಾರ/ಊಟ|ഭക്ഷണം|খাবার|अन्न/जेवण|ખોરાક/ભોજન|ਖਾਣਾ|ଖାଦ୍ୟ
medicine|N|medicine|மருந்து/மருந்துகள்|दवा/दवाई|మందులు/మందు|ಔಷಧ/ಔಷಧಿ|മരുന്ന്|ওষুধ|औषध/औषधे|દવા|ਦਵਾਈ|ଔଷଧ
blanket|N|blankets|போர்வை/போர்வைகள்|कंबल|దుప్పట్లు|ಕಂಬಳಿ|പുതപ്പ്|কম্বল|ब्लँकेट/चादर|ધાબળો/ધાબળા|ਕੰਬਲ|କମ୍ବଳ
boat|N|boat|படகு|नाव|పడవ|ದೋಣಿ|ബോട്ട്|নৌকা|बोट/नाव|હોડી|ਕਿਸ਼ਤੀ|ଡଙ୍ଗା
helicopter|N|helicopter|ஹெலிகாப்டர்|हेलीकॉप्टर|హెలికాప్టర్|ಹೆಲಿಕಾಪ್ಟರ್|ഹെലികോപ്റ്റർ|হেলিকপ্টার|हेलिकॉप्टर|હેલિકોપ્ટર|ਹੈਲੀਕਾਪਟਰ|ହେଲିକପ୍ଟର
road|N|road|சாலை|सड़क|రోడ్డు|ರಸ್ತೆ|റോഡ്|রাস্তা|रस्ता|રસ્તો|ਸੜਕ|ରାସ୍ତା
bridge|N|bridge|பாலம்|पुल|వంతెన/బ్రిడ్జి|ಸೇತುವೆ|പാലം|সেতু/ব্রিজ|पूल|પુલ|ਪੁਲ|ସେତୁ
building|N|building|கட்டிடம்|इमारत|భవనం|ಕಟ್ಟಡ|കെട്ടിടം|ভবন/বিল্ডিং|इमारत|મકાન/ઇમારત|ਇਮਾਰਤ|ଅଟ୍ଟାଳିକା
house|N|house|வீடு|घर|ఇల్లు|ಮನೆ|വീട്|বাড়ি|घर|ઘર|ਘਰ|ଘର
fire|N|fire|தீ|आग|మంటలు/అగ్ని|ಬೆಂಕಿ|തീ|আগুন|आग|આગ|ਅੱਗ|ନିଆଁ
flood|N|flood|வெள்ளம்|बाढ़|వరద|ಪ್ರವಾಹ|വെള്ളപ്പൊക്കം|বন্যা|पूर|પૂર|ਹੜ੍ਹ|ବନ୍ୟା
shelter|N|shelter|தங்குமிடம்|आश्रय|ఆశ్రయం|ಆಶ್ರಯ|അഭയകേന്ദ്രം|আশ্রয়|निवारा|આશ્રય|ਪਨਾਹ|ଆଶ୍ରୟ
danger|N|danger|ஆபத்து|खतरा|ప్రమాదం|ಅಪಾಯ|അപകടം|বিপদ|धोका|જોખમ|ਖ਼ਤਰਾ|ବିପଦ
emergency|N|emergency|அவசரம்/அவசர நிலை|आपातकाल|అత్యవసరం|ತುರ್ತು|അടിയന്തരം|জরুরি|आणीबाणी|કટોકટી|ਐਮਰਜੈਂਸੀ|ଜରୁରୀକାଳୀନ
electricity|N|electricity|மின்சாரம்|बिजली|కరెంట్/విద్యుత్|ವಿದ್ಯುತ್|വൈദ്യുതി|বিদ্যুৎ|वीज|વીજળી|ਬਿਜਲੀ|ବିଦ୍ୟୁତ୍
help_n|N|help|உதவி|मदद/सहायता|సహాయం|ಸಹಾಯ|സഹായം|সাহায্য|मदत|મદદ|ਮਦਦ|ସାହାଯ୍ୟ
two|N|two|இரண்டு|दो|రెండు|ಎರಡು|രണ്ട്|দুই|दोन|બે|ਦੋ|ଦୁଇ
three|N|three|மூன்று|तीन|మూడు|ಮೂರು|മൂന്ന്|তিন|तीन|ત્રણ|ਤਿੰਨ|ତିନି
five|N|five|ஐந்து|पाँच|ఐదు|ಐದು|അഞ്ച്|পাঁচ|पाच|પાંચ|ਪੰਜ|ପାଞ୍ଚ
help_v|V|help|உதவுங்கள்/உதவுங்க/உதவி செய்யுங்கள்|मदद करें/मदद कीजिए/मदद करो|సహాయం చేయండి|ಸಹಾಯ ಮಾಡಿ|സഹായിക്കൂ/സഹായിക്കുക|সাহায্য করুন|मदत करा|મદદ કરો|ਮਦਦ ਕਰੋ|ସାହାଯ୍ୟ କରନ୍ତୁ
come|V|come|வாருங்கள்/வாங்க/வா/வரவும்|आइए/आओ/आइये/आएं|రండి|ಬನ್ನಿ|വരൂ/വരുക|আসুন/এসো|या/ये|આવો|ਆਓ|ଆସନ୍ତୁ
come_and|C|come and|வந்து|आकर|వచ్చి|ಬಂದು|വന്ന്|এসে|येऊन|આવીને|ਆ ਕੇ|ଆସି
send|V|send|அனுப்புங்கள்|भेजिए/भेजें/भेजो|పంపండి|ಕಳುಹಿಸಿ|അയയ്ക്കൂ/അയക്കുക|পাঠান|पाठवा|મોકલો|ਭੇਜੋ|ପଠାନ୍ତୁ
bring|V|bring|கொண்டு வாருங்கள்|लाइए/लाओ|తీసుకురండి|ತನ್ನಿ|കൊണ്ടുവരൂ|আনুন|आणा|લાવો|ਲਿਆਓ|ଆଣନ୍ତୁ
wait|V|wait|காத்திருங்கள்/காத்திருங்க|रुकिए/इंतज़ार करें|వేచి ఉండండి|ಕಾಯಿರಿ|കാത്തിരിക്കൂ|অপেক্ষা করুন|थांबा|રાહ જુઓ|ਉਡੀਕ ਕਰੋ|ଅପେକ୍ଷା କରନ୍ତୁ
save|V|save|காப்பாற்றுங்கள்|बचाइए/बचाओ|కాపాడండి|ಕಾಪಾಡಿ|രക്ഷിക്കൂ|বাঁচান|वाचवा|બચાવો|ਬਚਾਓ|ବଞ୍ଚାନ୍ତୁ
go|V|go|செல்லுங்கள்/போங்கள்|जाइए/जाओ|వెళ్ళండి|ಹೋಗಿ|പോകൂ|যান|जा|જાઓ|ਜਾਓ|ଯାନ୍ତୁ
stay|V|stay|இருங்கள்|रहिए|ఉండండి|ಇರಿ|ഇരിക്കൂ|থাকুন|राहा|રહો|ਰਹੋ|ରୁହନ୍ତୁ
need|E|need|வேணும்/வேண்டும்/தேவை|चाहिए/चाहिये|కావాలి|ಬೇಕು|വേണം|চাই/দরকার|हवे/हवी|જોઈએ|ਚਾਹੀਦਾ|ଦରକାର
injured|S|injured|காயமடைந்துள்ளார்/காயமடைந்துள்ளனர்/காயமடைந்தார்/காயம்/அடிபட்டுள்ளது|घायल|గాయపడ్డారు/గాయపడ్డాడు/గాయపడింది/గాయమైంది|ಗಾಯಗೊಂಡಿದ್ದಾರೆ/ಗಾಯವಾಗಿದೆ/ಗಾಯಗೊಂಡಿದೆ|പരിക്കേറ്റു/പരിക്കേറ്റിട്ടുണ്ട്|আহত|जखमी|ઇજાગ્રસ્ત/ઘાયલ|ਜ਼ਖਮੀ|ଆହତ
trapped|S|trapped|சிக்கியுள்ளனர்/@we:சிக்கிக்கொண்டோம்/சிக்கிக்கொண்டோம்/சிக்கித் தவிக்கின்றனர்/சிக்கியுள்ளார்|फंसे हुए/फंसे|చిక్కుకున్నారు/@we:చిక్కుకున్నాం/చిక్కుకున్నాం|ಸಿಲುಕಿದ್ದಾರೆ/@we:ಸಿಲುಕಿಕೊಂಡಿದ್ದೇವೆ/ಸಿಲುಕಿಕೊಂಡಿದ್ದೇವೆ|കുടുങ്ങി|আটকা পড়েছে/আটকে|अडकले|ફસાયેલા|ਫਸੇ|ଫସିଛନ୍ତି/ଫସି
safe|S|safe|பாதுகாப்பாக|सुरक्षित|సురక్షితంగా|ಸುರಕ್ಷಿತ|സുരക്ഷിതം|নিরাপদ|सुरक्षित|સુરક્ષિત|ਸੁਰੱਖਿਅਤ|ସୁରକ୍ଷିତ
blocked|S|blocked|முடக்கப்பட்டுள்ளது/தடைபட்டுள்ளது|बंद|మూసుకుపోయింది/నిలిచిపోయింది|ಮುಚ್ಚಿದೆ|തടസ്സപ്പെട്ടു|বন্ধ|बंद|બંધ|ਬੰਦ|ବନ୍ଦ
collapsed|S|~has collapsed|இடிந்து விழுந்தது/இடிந்தது|गिर गया/ढह गया|కూలిపోయింది|ಕುಸಿದಿದೆ|തകർന്നു|ভেঙে পড়েছে|कोसळला/कोसळली|તૂટી પડ્યો|ਡਿੱਗ ਗਿਆ|ଭାଙ୍ଗି ପଡ଼ିଛି
unconscious|S|unconscious|மயக்கமடைந்துள்ளார்/மயங்கி விழுந்தார்|बेहोश|అపస్మారక స్థితిలో|ಪ್ರಜ್ಞೆ ತಪ್ಪಿದ್ದಾರೆ|ബോധരഹിതനാണ്|অজ্ঞান|बेशुद्ध|બેભાન|ਬੇਹੋਸ਼|ଅଜ୍ଞାନ
rising|S|~is rising|உயர்கிறது/உயர்ந்து வருகிறது|बढ़ रहा है|పెరుగుతోంది|ಹೆಚ್ಚುತ್ತಿದೆ|ഉയരുന്നു|বাড়ছে|वाढत आहे|વધી રહ્યું છે|ਵੱਧ ਰਿਹਾ ਹੈ|ବଢ଼ୁଛି
here|A|here|இங்கே/இங்க|यहाँ/यहां|ఇక్కడ|ಇಲ್ಲಿ|ഇവിടെ|এখানে|इथे/येथे|અહીં|ਇੱਥੇ|ଏଠାରେ
there|A|there|அங்கே/அங்க|वहाँ|అక్కడ|ಅಲ್ಲಿ|അവിടെ|ওখানে|तिथे|ત્યાં|ਉੱਥੇ|ସେଠାରେ
quickly|A|quickly|சீக்கிரம்/விரைவாக/விரைந்து|जल्दी|త్వరగా|ಬೇಗ|വേഗം|তাড়াতাড়ি|लवकर|જલ્દી|ਜਲਦੀ|ଶୀଘ୍ର
immediately|A|immediately|உடனடியாக/உடனே|तुरंत/फौरन|వెంటనే|ತಕ್ಷಣ|ഉടനെ|অবিলম্বে/এখনই|ताबडतोब/लगेच|તરત|ਤੁਰੰਤ|ତୁରନ୍ତ
now|A|now|இப்போது/இப்போ|अब|ఇప్పుడు|ಈಗ|ഇപ്പോൾ|এখন|आता|હવે|ਹੁਣ|ଏବେ
everyone|A|everyone|எல்லாரும்/அனைவரும்/எல்லோரும்|सब/सभी|అందరూ|ಎಲ್ಲರೂ|എല്ലാവരും|সবাই|सर्व/सगळे|બધા|ਸਾਰੇ|ସମସ୍ତେ
please|D|please|தயவுசெய்து|कृपया|దయచేసి|ದಯವಿಟ್ಟು|ദയവായി|দয়া করে|कृपया|કૃપા કરીને|ਕਿਰਪਾ ਕਰਕੇ|ଦୟାକରି
thanks|D|thank you|நன்றி|धन्यवाद|ధన్యవాదాలు|ಧನ್ಯವಾದಗಳು|നന്ദി|ধন্যবাদ|धन्यवाद|આભાર|ਧੰਨਵਾਦ|ଧନ୍ୟବାଦ
yes|D|yes|ஆம்|हाँ|అవును|ಹೌದು|അതെ|হ্যাঁ|होय|હા|ਹਾਂ|ହଁ
no|D|no|இல்லை|नहीं|లేదు|ಇಲ್ಲ|ഇല്ല|না|नाही|ના|ਨਹੀਂ|ନା
where|Q|where|எங்கே/எங்க|कहाँ|ఎక్కడ|ಎಲ್ಲಿ|എവിടെ|কোথায়|कुठे|ક્યાં|ਕਿੱਥੇ|କେଉଁଠି
what|Q|what|என்ன|क्या|ఏమి/ఏం|ಏನು|എന്ത്|কী/কি|काय|શું|ਕੀ|କଣ
how many|Q|how many|எத்தனை|कितने|ఎంత మంది|ಎಷ್ಟು|എത്ര|কতজন|किती|કેટલા|ਕਿੰਨੇ|କେତେ
be|F||உள்ளது|है/हैं/हूँ/हो/था|ఉంది/ఉన్నారు|ಇದೆ/ಇದ್ದಾರೆ|ഉണ്ട്|আছে/হয়েছে|आहे/आहेत/आहात|છે/છો/છું/છીએ|ਹੈ/ਹਨ/ਹਾਂ|ଅଛି/ଅଟେ
"""

    // Adjectival state predicates need a finite copula in these target languages (the others carry it in the verb form).
    private val copula = mapOf(
        Language.HINDI to "है", Language.MARATHI to "आहे", Language.GUJARATI to "છે",
        Language.PUNJABI to "ਹੈ", Language.ODIA to "ଅଛି"
    )
    private val pluralCopula = mapOf(
        Language.HINDI to "हैं", Language.MARATHI to "आहेत", Language.PUNJABI to "ਹਨ", Language.ODIA to "ଅଛନ୍ତି"
    )
    // Languages whose S-forms above are adjectives (need the copula appended when they are the target).
    private val adjectivalS = setOf(Language.HINDI, Language.MARATHI, Language.GUJARATI, Language.PUNJABI, Language.ODIA)

    private val langOrder = listOf(
        Language.TAMIL, Language.HINDI, Language.TELUGU, Language.KANNADA, Language.MALAYALAM,
        Language.BENGALI, Language.MARATHI, Language.GUJARATI, Language.PUNJABI, Language.ODIA
    )

    /** [weForms] are first-person-plural variants ("@we:" in the data) used when the subject is "we". */
    private class Concept(
        val id: String, val type: Char, val en: String,
        val forms: List<List<String>>, val weForms: List<String?>
    )

    private fun String.nfc(): String = Normalizer.normalize(this, Normalizer.Form.NFC)

    private const val WE_TAG = "@we:"

    private val concepts: List<Concept> by lazy {
        DATA.lines().filter { it.isNotBlank() }.map { line ->
            val c = line.split("|")
            val raw = (3..12).map { i -> c[i].split("/").filter { it.isNotBlank() }.map { it.nfc() } }
            Concept(
                c[0], c[1][0], c[2].nfc(),
                raw.map { l -> l.filter { !it.startsWith(WE_TAG) }.map { it.removePrefix(WE_TAG) } },
                raw.map { l -> l.firstOrNull { it.startsWith(WE_TAG) }?.removePrefix(WE_TAG) }
            )
        }
    }

    // language -> surface form -> concept (later rows never override earlier ones)
    private val index: Map<Language, Map<String, Concept>> by lazy {
        langOrder.mapIndexed { li, lang ->
            val m = HashMap<String, Concept>()
            for (c in concepts) for (f in c.forms[li]) m.putIfAbsent(f, c)
            lang to m
        }.toMap()
    }

    private val punct = Regex("""[.,!?;:।॥"'()\[\]{}]""")

    private fun tokenize(text: String): List<String> =
        text.nfc().replace(punct, " ").split(Regex("""\s+""")).filter { it.isNotBlank() }

    /** Longest-span exact match, then inflection-tolerant prefix match. Null = unknown word. */
    private fun match(tokens: List<String>, i: Int, map: Map<String, Concept>): Pair<Concept, Int>? {
        for (span in minOf(3, tokens.size - i) downTo 1) {
            val key = tokens.subList(i, i + span).joinToString(" ")
            map[key]?.let { return it to span }
        }
        val t = tokens[i]
        for (len in t.length - 1 downTo 3) {
            if (len * 2 < t.length) break
            map[t.substring(0, len)]?.let { return it to 1 }
        }
        return null
    }

    fun translate(text: String, source: Language, target: Language): String? {
        val li = langOrder.indexOf(source)
        val ti = langOrder.indexOf(target)
        if (li < 0 || (ti < 0 && target != Language.ENGLISH) || source == target) return null
        val map = index[source] ?: return null
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return null

        val seq = ArrayList<Concept>()
        var i = 0
        while (i < tokens.size) {
            val tok = tokens[i]
            if (tok.all { it.isDigit() }) { seq.add(Concept("num", 'N', tok, List(10) { listOf(tok) }, List(10) { null })); i++; continue }
            val (c, span) = match(tokens, i, map) ?: return null
            seq.add(c)
            i += span
        }
        if (seq.all { it.type == 'F' }) return null

        return if (target == Language.ENGLISH) renderEnglish(seq) else renderIndic(seq, ti, target)
    }

    private fun renderIndic(seq: List<Concept>, ti: Int, target: Language): String? {
        val out = ArrayList<String>()
        val weSubject = seq.any { it.id == "we" }
        val plural = seq.any { it.id in setOf("we", "you", "they", "people") }
        for (c in seq) {
            if (c.type == 'F') continue
            val form = (if (weSubject) c.weForms[ti] else null) ?: c.forms[ti].firstOrNull() ?: return null
            out.add(form)
            if (c.type == 'S' && target in adjectivalS) {
                val cop = if (plural) pluralCopula[target] ?: copula[target] else copula[target]
                cop?.let { out.add(it) }
            }
        }
        return out.joinToString(" ").takeIf { it.isNotBlank() }
    }

    // ---- Indic -> English -------------------------------------------------------------------------------

    private val pluralSubjects = setOf("we", "you", "they", "people", "elderly people", "blankets")

    private fun beForm(subject: String?): String = when {
        subject == null -> "is"
        subject.equals("I", true) -> "am"
        subject.lowercase(Locale.ROOT) in pluralSubjects -> "are"
        else -> "is"
    }

    private fun subjectForm(c: Concept): String = when (c.id) { "us" -> "we"; "me" -> "I"; else -> c.en }

    private fun renderEnglish(seq: List<Concept>): String? {
        val words = seq.filter { it.type != 'F' }
        // Split into clauses: SOV languages end a clause at its predicate; 'C' (come-and) chains onward.
        val clauses = ArrayList<MutableList<Concept>>()
        var cur = ArrayList<Concept>()
        for (c in words) {
            cur.add(c)
            if (c.type == 'V' || c.type == 'S' || c.type == 'E') { clauses.add(cur); cur = ArrayList() }
        }
        if (cur.isNotEmpty()) clauses.add(cur)

        val sentences = clauses.mapNotNull { renderClause(it) }
        if (sentences.isEmpty()) return null
        return sentences.joinToString(" ")
    }

    private fun renderClause(cl: List<Concept>): String? {
        val please = cl.filter { it.type == 'D' }.joinToString(" ") { it.en }
        val q = cl.firstOrNull { it.type == 'Q' }
        val pred = cl.lastOrNull { it.type == 'V' || it.type == 'S' || it.type == 'E' }
        val chain = cl.filter { it.type == 'C' }.joinToString(" ") { it.en }
        val adverbs = cl.filter { it.type == 'A' }.joinToString(" ") { it.en }
        val nouns = cl.filter { it.type == 'N' }
        val obliques = cl.filter { it.type == 'O' }
        val persons = cl.filter { it.type == 'P' }

        val body: String
        var question = false
        when (pred?.type) {
            'S' -> {
                val subj = persons.firstOrNull()?.en ?: nouns.firstOrNull()?.let { "the ${it.en}" }
                val be = if (pred.en.startsWith("~")) "" else beForm(persons.firstOrNull()?.en ?: nouns.firstOrNull()?.en)
                val pe = pred.en.removePrefix("~")
                val mid = listOfNotNull(subj, be.ifBlank { null }, pe).joinToString(" ")
                body = listOf(mid, adverbs).filter { it.isNotBlank() }.joinToString(" ")
            }
            'E' -> {
                val subj = persons.firstOrNull()?.en ?: obliques.firstOrNull()?.let { subjectForm(it) } ?: "we"
                val objs = nouns.joinToString(" ") { it.en }
                body = listOf(subj, pred.en, objs, adverbs).filter { it.isNotBlank() }.joinToString(" ")
            }
            'V' -> {
                val subj = persons.firstOrNull()?.en
                val objs = (obliques.map { it.en } + nouns.map { it.en }).joinToString(" ")
                val verb = listOf(chain, pred.en).filter { it.isNotBlank() }.joinToString(" ")
                body = listOf(subj ?: "", verb, objs, adverbs).filter { it.isNotBlank() }.joinToString(" ")
            }
            else -> {
                // Verbless fragment: "where <noun>?" or a bare noun phrase.
                val rest = (persons.map { it.en } + obliques.map { it.en } + nouns.map { it.en }).joinToString(" ")
                if (q != null) {
                    question = true
                    val subject = persons.firstOrNull()?.en ?: nouns.firstOrNull()?.en
                    val be = beForm(subject)
                    val restNp = if (persons.isEmpty() && nouns.isNotEmpty()) "the $rest" else rest
                    body = listOf(q.en, be, restNp, adverbs).filter { it.isNotBlank() }.joinToString(" ")
                } else {
                    body = listOf(rest, adverbs).filter { it.isNotBlank() }.joinToString(" ")
                }
            }
        }
        if (body.isBlank() && please.isBlank()) return null
        val sentence = listOf(please, body).filter { it.isNotBlank() }.joinToString(" ")
        val cap = sentence.replaceFirstChar { it.titlecase(Locale.ROOT) }
        return cap + if (question || q != null) "?" else "."
    }
}
