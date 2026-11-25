package com.nexa.app.presentation.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.presentation.common.glass.NexaGlassSurface

// ============================================================
// EMOJI CATEGORY
// ============================================================

enum class EmojiCategory(
    val label: String,
    val icon: String
) {
    RECENT("Recent", "🕒"),
    FACES("Faces", "😀"),
    LOVE("Love", "❤️"),
    HANDS("Hands", "👍"),
    NATURE("Nature", "🌿"),
    SPACE("Space", "🪐"),
    ANIMALS("Animals", "🐶"),
    FOOD("Food", "🍕"),
    ACTION("Action", "🎬"),
    OBJECTS("Objects", "🧰"),
    SYMBOLS("Symbols", "🔣"),
    FLAGS("Flags", "🏳️")
}

enum class PickerTab {
    EMOJIS,
    GIFS_MEMES
}

// ============================================================
// FLAG GENERATOR
// ============================================================

private fun flagEmoji(countryCode: String): String {
    return countryCode
        .uppercase()
        .map { character ->
            Character.toChars(
                0x1F1E6 + (character - 'A')
            ).concatToString()
        }
        .joinToString("")
}

private val POPULAR_FLAGS = listOf(
    "🇮🇳", "🇺🇸", "🇬🇧", "🇨🇦", "🇦🇺", "🇳🇿",
    "🇯🇵", "🇨🇳", "🇰🇷", "🇩🇪", "🇫🇷", "🇮🇹",
    "🇪🇸", "🇷🇺", "🇺🇦", "🇧🇷", "🇦🇪", "🇸🇦",
    "🇶🇦", "🇸🇬", "🇲🇾", "🇹🇭", "🇮🇩", "🇵🇭",
    "🇻🇳", "🇵🇰", "🇧🇩", "🇳🇵", "🇱🇰", "🇿🇦",
    "🇹🇷", "🇲🇽", "🇦🇷", "🇵🇹", "🇳🇱", "🇨🇭",
    "🇸🇪", "🇳🇴", "🇩🇰", "🇫🇮", "🇮🇪", "🇮🇱",
    "🇬🇷", "🇪🇬", "🇳🇬", "🇰🇪"
)

private val COUNTRY_CODES = """
    AC AD AE AF AG AI AL AM AO AQ AR AS AT AU AW AX AZ
    BA BB BD BE BF BG BH BI BJ BL BM BN BO BQ BR BS BT BV BW BY BZ
    CA CC CD CF CG CH CI CK CL CM CN CO CP CR CU CV CW CX CY CZ
    DE DG DJ DK DM DO DZ EA EC EE EG EH ER ES ET EU FI FJ FK FM FO FR
    GA GB GD GE GF GG GH GI GL GM GN GP GQ GR GS GT GU GW GY
    HK HM HN HR HT HU IC ID IE IL IM IN IO IQ IR IS IT
    JE JM JO JP KE KG KH KI KM KN KP KR KW KY KZ
    LA LB LC LI LK LR LS LT LU LV LY
    MA MC MD ME MF MG MH MK ML MM MN MO MP MQ MR MS MT MU MV MW MX
    MZ NA NC NE NF NG NI NL NO NP NR NU NZ
    OM PA PE PF PG PH PK PL PM PN PR PS PT PW PY
    QA RE RO RS RU RW
    SA SB SC SD SE SG SH SI SJ SK SL SM SN SO SR SS ST SV SX SY SZ
    TC TD TF TG TH TJ TK TL TM TN TO TR TT TV TW TZ
    UA UG UM US UY UZ VA VC VE VG VI VN VU WF WS XK YE YT ZA ZM ZW
""".trimIndent()
    .split(Regex("\\s+"))
    .filter { it.length == 2 }
    .distinct()

private val ALL_COUNTRY_FLAGS = COUNTRY_CODES.map(::flagEmoji)

private val COMPLETE_FLAGS = (
        POPULAR_FLAGS + ALL_COUNTRY_FLAGS
        ).distinct()

// ============================================================
// CURATED GIF / MEME PRESETS
// ============================================================

private val CURATED_GIF_PRESETS = listOf(
    "👍 Thumbs Up",
    "👏 Applause",
    "😂 Laughing",
    "🔥 Fire",
    "🎉 Celebration",
    "🤯 Mind Blown",
    "🚀 Let's Go",
    "👀 Looking",
    "💯 Exactly",
    "🫡 Understood",
    "🤝 Agreed",
    "✨ Magic"
)

// ============================================================
// EMOJI DATA
// ============================================================

private val EMOJI_DATA: Map<EmojiCategory, List<String>> = mapOf(

    // 01. Faces & Emotions
    EmojiCategory.FACES to listOf(
        "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣",
        "😊", "😇", "🙂", "🙃", "😉", "😌", "😍", "🥰",
        "😘", "😗", "😙", "😚", "😋", "😛", "😝", "😜", "🤪",
        "🤨", "🧐", "🤓", "😎", "🤩", "🥳",
        "😏", "😒", "😞", "😔", "😟", "😕", "🙁", "☹️",
        "😣", "😖", "😫", "😩", "🥺", "😢", "😭",
        "😤", "😠", "😡", "🤬", "🤯", "😳", "🫣",
        "😱", "😨", "😰", "😥", "😓", "🫢", "😮", "😯", "😲",
        "😶", "🫥", "😐", "😑", "😬", "🙄",
        "😴", "🥱", "😪", "🤤",
        "🤔", "🫡", "🤗", "🤭",
        "🫠", "🥲",
        "😷", "🤒", "🤕", "🤢", "🤮", "🤧",
        "😵", "😵‍💫", "🤐", "🤫", "🫨",
        "😈", "👿", "👹", "👺", "🤡", "💀", "☠️",
        "👻", "👽", "🤖"
    ),

    // 02. Hearts & Love
    EmojiCategory.LOVE to listOf(
        "❤️", "🩷", "🧡", "💛", "💚", "💙", "💜", "🖤",
        "🤍", "🤎", "🩵", "🩶",
        "💔", "❤️‍🔥", "❤️‍🩹",
        "💕", "💞", "💓", "💗", "💖", "💘", "💝", "💟",
        "❣️", "💌", "💋"
    ),

    // 03. Hands & Reactions
    EmojiCategory.HANDS to listOf(
        "👍", "👎", "👌", "✌️", "🤞", "🤟", "🤘", "🤙",
        "👋", "🤚", "🖐️", "✋", "🖖",
        "👏", "🙌", "👐", "🤲", "🙏",
        "🤝", "🫶", "💪",
        "👈", "👉", "👆", "👇", "☝️",
        "🫵", "🫰", "🤌", "🤏",
        "✍️", "🙋", "🙇",
        "🤦", "🤷", "🫂"
    ),

    // 04. Nature
    EmojiCategory.NATURE to listOf(
        "🌱", "🌿", "☘️", "🍀", "🎍", "🪴", "🌵", "🎋",
        "🌷", "🌹", "🥀", "🌺", "🌸", "🌼", "🌻", "💐", "🪻",
        "🌲", "🌳", "🌴", "🎄",
        "🍃", "🍂", "🍁",
        "☀️", "🌤️", "⛅", "🌥️", "☁️", "🌦️", "🌧️",
        "⛈️", "🌩️", "🌨️", "❄️", "🌪️", "🌫️",
        "🌈", "💧", "💦", "🌊", "🪨",
        "🦋", "🐝", "🐞", "🪲", "🐜", "🦗", "🕷️", "🦂",
        "🐌", "🐛", "🪱"
    ),

    // 05. Planets & Space
    EmojiCategory.SPACE to listOf(
        "☀️", "🌑", "🌒", "🌓", "🌔", "🌕", "🌖", "🌗", "🌘",
        "🌙", "🌚", "🌛", "🌜",
        "🌍", "🌎", "🌏",
        "🪐", "⭐", "🌟", "🌠", "☄️", "💫",
        "🌌", "🚀", "👨‍🚀", "🛰️", "🔭", "🛸", "👽"
    ),

    // 06. Animals
    EmojiCategory.ANIMALS to listOf(
        "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻",
        "🐼", "🐨", "🐯", "🦁", "🐮", "🐷", "🐸",
        "🐵", "🙈", "🙉", "🙊",
        "🐔", "🐧", "🐦", "🦋",
        "🐠", "🐟", "🐬", "🐳",
        "🐢", "🐍"
    ),

    // 07. Food & Drink
    EmojiCategory.FOOD to listOf(
        "🍎", "🍏", "🍊", "🍋", "🍌", "🍉", "🍇", "🍓",
        "🍒", "🍑", "🥭", "🍍", "🥥",
        "🍔", "🍟", "🍕", "🌭", "🌮", "🌯",
        "🍿", "🍩", "🍪", "🎂", "🍰",
        "🍫", "🍬", "🍭",
        "☕", "🥤", "🧋", "🍺", "🍻"
    ),

    // 08. Action / Activities
    EmojiCategory.ACTION to listOf(
        "🏃", "🏃‍♀️", "🏃‍♂️",
        "🚶", "🚶‍♀️", "🚶‍♂️",
        "💃", "🕺",
        "🧘", "🧘‍♀️", "🧘‍♂️",
        "🤸", "🤸‍♀️", "🤸‍♂️",
        "🏋️", "🏋️‍♀️", "🏋️‍♂️",
        "🤾", "⛹️", "🏊", "🚴", "🤺",
        "👋", "🤝", "🙏", "👏", "🙌", "🫶",
        "💪", "✍️", "🤳",
        "💋", "💏", "💑", "🫂",
        "🍳", "🍽️", "☕",
        "🛒", "🛍️",
        "🎮", "🕹️", "🎲", "🃏", "🎯",
        "🎸", "🎹", "🥁", "🎺", "🎻",
        "🎤", "🎧", "🎬", "🎨",
        "⚽", "🏀", "🏈", "⚾", "🎾", "🏐", "🏉",
        "🏏", "🏓", "🏸", "🥊", "🥋", "⛳",
        "🏹", "🎣", "🏂", "⛷️", "🛹", "🛼",
        "🏆", "🥇", "🥈", "🥉", "🎖️", "🏅",
        "🏕️", "🏖️", "🏔️", "🧗"
    ),

    // 09. Objects
    EmojiCategory.OBJECTS to listOf(
        // Technology
        "📱", "💻", "🖥️", "🖨️", "⌨️", "🖱️", "🖲️",
        "📷", "📸", "📹", "📺", "📻", "☎️", "📞",
        "🎧", "🎤", "🔊", "🔋", "🔌", "💿", "💾",
        "⌚", "⏰", "⏱️", "⏳",

        // Money
        "💰", "💵", "💴", "💶", "💷", "💳", "🪙", "💎", "👑",

        // Gifts
        "🎁", "🎀", "🎈", "🎂", "🪅", "🧧", "🎊", "🎉",

        // Stationery
        "✏️", "🖊️", "🖋️", "📝", "📚", "📖", "📕", "📗", "📘", "📙",
        "📓", "📒", "📔", "📑", "🔖", "📌", "📍", "📎", "✂️",
        "📐", "📏", "🗂️", "📁",

        // Tools
        "🔨", "🪓", "⛏️", "🛠️", "⚒️", "🔧", "🔩", "⚙️",
        "🪛", "🧰", "🪚", "🔗", "🪜", "🧲",

        // Home
        "🛋️", "🪑", "🛏️", "🚪", "🪟", "🪞", "🛁", "🚿", "🚽",
        "🧻", "🧹", "🧺", "🧴", "🧼", "🪥", "🧽", "🕯️", "🛒",

        // Clothing
        "👕", "👖", "👗", "👔", "👚", "👒", "🧢", "🎩",
        "👟", "👞", "👠", "🥾", "🩴",
        "👜", "🎒", "👝", "💼", "👓", "🕶️", "☂️",

        // Security
        "🔒", "🔓", "🔑", "🗝️", "🔐", "🛡️",

        // Light & Electricity
        "💡", "🔦", "🕯️", "🔥", "🔋", "🔌", "⚡",

        // Communication
        "✉️", "📧", "💌", "📮", "📬", "📭", "📫", "📦",
        "📤", "📥", "📯",

        // Music
        "🎧", "🎤", "🎼", "🎵", "🎶", "🎸", "🎹",
        "🥁", "🎺", "🎻", "🪕", "📻",

        // Games
        "🎮", "🕹️", "🎲", "🧩", "♟️", "🃏", "🎯", "🎰", "🪀", "🪁",

        // Science & Medical
        "🔬", "🔭", "🧪", "🧫", "🧬", "⚗️", "💊", "💉", "🩹", "🩺", "🌡️",

        // Photography
        "📷", "📸", "📹", "🎥", "🎞️", "📺", "📼",

        // Travel
        "🧳", "🎒", "🗺️", "🧭", "🪪", "🎟️", "🎫", "🛂"
    ),

    // 10. Symbols
    EmojiCategory.SYMBOLS to listOf(
        // Love
        "❤️", "🩷", "🧡", "💛", "💚", "💙", "💜", "🖤",
        "🤍", "🤎", "💔", "💕", "💞", "💓", "💗", "💖",
        "💘", "💝", "💟", "❣️",

        // Alerts
        "❗", "❕", "❓", "❔", "‼️", "⁉️", "⚠️", "🚨",
        "⛔", "🚫", "❌", "❎",

        // Status
        "⭕", "✅", "☑️", "✔️", "✓", "✖️", "✕",
        "➕", "➖", "✳️", "❇️",

        // Stars
        "⭐", "🌟", "✨", "💫", "🔆", "🔅",
        "✴️", "❇️", "❈", "❉", "❊", "❋",

        // Arrows
        "⬆️", "⬇️", "⬅️", "➡️",
        "↗️", "↘️", "↙️", "↖️", "↔️", "↕️",
        "🔝", "🔜", "🔙", "🔛", "⤴️", "⤵️",

        // Math
        "➕", "➖", "✖️", "➗", "♾️", "💯",
        "%", "‰", "√", "∞", "≈", "≠", "≤", "≥",

        // Signs
        "♻️", "⚡", "☢️", "☣️", "⚕️",
        "♿", "🚻", "🚹", "🚺",
        "ⓘ", "ℹ️", "🔱", "⚜️", "☮️", "☯️",

        // Controls
        "🔴", "🟠", "🟡", "🟢", "🔵", "🟣", "🟤", "⚫", "⚪",
        "🔘", "🔳", "🔲",
        "▶️", "⏸️", "⏯️", "⏹️", "⏺️",
        "⏪", "⏩", "⏮️", "⏭️", "🔀", "🔁", "🔂",

        // Zodiac
        "♈", "♉", "♊", "♋", "♌", "♍", "♎", "♏", "♐", "♑", "♒", "♓",

        // Legal
        "©️", "®️", "™️",

        // Numbers
        "#️⃣", "*️⃣",
        "0️⃣", "1️⃣", "2️⃣", "3️⃣", "4️⃣",
        "5️⃣", "6️⃣", "7️⃣", "8️⃣", "9️⃣", "🔟", "🔢"
    ),

    // 11. Flags
    EmojiCategory.FLAGS to COMPLETE_FLAGS
)

// ============================================================
// SEARCH KEYWORDS
// ============================================================

private val EMOJI_SEARCH_TERMS = mapOf(
    "😀" to "grinning face smile happy",
    "😃" to "smiley happy",
    "😄" to "smile happy",
    "😁" to "beaming grin",
    "😆" to "laughing funny",
    "😅" to "nervous sweat smile",
    "😂" to "laugh cry funny tears",
    "🤣" to "rolling laughing",
    "😊" to "blush happy smile",
    "😇" to "angel innocent",
    "🙂" to "slight smile",
    "🙃" to "upside down",
    "😉" to "wink",
    "😍" to "love heart eyes",
    "🥰" to "love hearts",
    "😘" to "kiss",
    "😋" to "yum delicious",
    "😎" to "cool sunglasses",
    "🤩" to "star eyes excited",
    "🥳" to "party celebration",
    "😏" to "smirk",
    "😢" to "sad crying",
    "😭" to "crying tears",
    "😡" to "angry mad",
    "🤯" to "mind blown shock",
    "😱" to "scream scared",
    "🤔" to "thinking",
    "🤗" to "hug",
    "😴" to "sleep sleeping",
    "🤫" to "quiet silence",
    "🤖" to "robot",
    "❤️" to "red heart love",
    "🩷" to "pink heart",
    "🧡" to "orange heart",
    "💛" to "yellow heart",
    "💚" to "green heart",
    "💙" to "blue heart",
    "💜" to "purple heart",
    "🖤" to "black heart",
    "🤍" to "white heart",
    "🤎" to "brown heart",
    "💔" to "broken heart heartbreak",
    "💕" to "two hearts love",
    "💞" to "revolving hearts",
    "💓" to "beating heart",
    "💖" to "sparkling heart",
    "💘" to "heart arrow",
    "💝" to "heart gift",
    "👍" to "thumbs up like approve",
    "👎" to "thumbs down dislike",
    "👏" to "clap applause",
    "🙏" to "pray thanks please",
    "🤝" to "handshake agreement",
    "💪" to "strong strength muscle",
    "👋" to "wave hello goodbye",
    "🫶" to "heart hands love",
    "🌱" to "seedling plant",
    "🌿" to "herb leaf nature",
    "🌹" to "rose flower",
    "🌻" to "sunflower",
    "🌈" to "rainbow",
    "☀️" to "sun sunny",
    "🌧️" to "rain",
    "❄️" to "snow winter",
    "🌍" to "earth world globe",
    "🌙" to "moon night",
    "⭐" to "star",
    "🚀" to "rocket space",
    "🐶" to "dog puppy",
    "🐱" to "cat kitten",
    "🦊" to "fox",
    "🐻" to "bear",
    "🐼" to "panda",
    "🦁" to "lion",
    "🐸" to "frog",
    "🐵" to "monkey",
    "🐧" to "penguin",
    "🐬" to "dolphin",
    "🐳" to "whale",
    "🍎" to "apple fruit",
    "🍌" to "banana",
    "🍉" to "watermelon",
    "🍓" to "strawberry",
    "🍕" to "pizza",
    "🍔" to "burger hamburger",
    "🍟" to "fries",
    "🍩" to "donut",
    "☕" to "coffee",
    "🧋" to "bubble tea",
    "🎂" to "cake birthday",
    "🎮" to "game gaming",
    "🎸" to "guitar music",
    "🎤" to "microphone singing",
    "🎧" to "headphones music",
    "⚽" to "football soccer",
    "🏏" to "cricket",
    "🏆" to "trophy winner",
    "📱" to "phone mobile",
    "💻" to "computer laptop",
    "📷" to "camera photo",
    "🔒" to "lock security",
    "🔑" to "key",
    "💡" to "light bulb idea",
    "🔥" to "fire hot",
    "🎁" to "gift present",
    "📚" to "books study",
    "🧪" to "science experiment",
    "💊" to "medicine pill",
    "✈️" to "airplane flight",
    "🇮🇳" to "india indian flag",
    "🇺🇸" to "usa america united states flag",
    "🇬🇧" to "uk britain england flag",
    "🇨🇦" to "canada flag",
    "🇯🇵" to "japan flag",
    "🇳🇵" to "nepal flag",
    "🇵🇰" to "pakistan flag",
    "🇧🇩" to "bangladesh flag"
)

// ============================================================
// EMOJI PICKER
// ============================================================

@Composable
fun NexaEmojiPicker(
    onEmojiSelected: (String) -> Unit,
    onGifSelected: ((String) -> Unit)? = null,
    onPickCustomGif: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()

    var currentTab by remember {
        mutableStateOf(PickerTab.EMOJIS)
    }

    var selectedCategory by remember {
        mutableStateOf(EmojiCategory.RECENT)
    }

    var searchQuery by remember {
        mutableStateOf("")
    }

    // Recent emoji list for current picker session.
    val recentEmojis = remember {
        mutableStateListOf<String>()
    }

    val surfaceColor = if (isDark) {
        Color(0xEE1A1A1A)
    } else {
        Color(0xEEF8F9FA)
    }

    val contentColor = if (isDark) {
        Color.White
    } else {
        Color(0xFF111111)
    }

    val allEmojis = remember {
        EMOJI_DATA.values.flatten().distinct()
    }

    val displayedEmojis = remember(
        selectedCategory,
        searchQuery,
        recentEmojis.toList()
    ) {
        if (searchQuery.isBlank()) {
            when (selectedCategory) {
                EmojiCategory.RECENT -> recentEmojis.toList()

                else -> EMOJI_DATA[selectedCategory].orEmpty()
            }
        } else {
            val query = searchQuery.trim().lowercase()

            allEmojis.filter { emoji ->
                emoji.contains(query) ||
                        EMOJI_SEARCH_TERMS[emoji]
                            ?.contains(query, ignoreCase = true) == true ||
                        EmojiCategory.entries.any { category ->
                            category.label.contains(query, ignoreCase = true) &&
                                    EMOJI_DATA[category]?.contains(emoji) == true
                        }
            }.distinct().take(200)
        }
    }

    NexaGlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .height(300.dp),
        shape = RoundedCornerShape(
            topStart = 24.dp,
            topEnd = 24.dp
        ),
        surfaceColor = surfaceColor,
        elevation = 8.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {

            // ====================================================
            // MAIN TAB SWITCHER
            // ====================================================

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.Center
            ) {

                Box(
                    modifier = Modifier
                        .clickable {
                            currentTab = PickerTab.EMOJIS
                        }
                        .background(
                            if (currentTab == PickerTab.EMOJIS) {
                                MaterialTheme.colorScheme.primary.copy(
                                    alpha = 0.2f
                                )
                            } else {
                                Color.Transparent
                            },
                            RoundedCornerShape(12.dp)
                        )
                        .padding(
                            horizontal = 16.dp,
                            vertical = 6.dp
                        )
                ) {
                    Text(
                        text = "😊 Emojis",
                        fontWeight = if (
                            currentTab == PickerTab.EMOJIS
                        ) {
                            FontWeight.Bold
                        } else {
                            FontWeight.Normal
                        },
                        color = if (
                            currentTab == PickerTab.EMOJIS
                        ) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            contentColor
                        },
                        fontSize = 13.sp
                    )
                }

                Spacer(
                    modifier = Modifier.width(8.dp)
                )

                Box(
                    modifier = Modifier
                        .clickable {
                            currentTab = PickerTab.GIFS_MEMES
                        }
                        .background(
                            if (currentTab == PickerTab.GIFS_MEMES) {
                                MaterialTheme.colorScheme.primary.copy(
                                    alpha = 0.2f
                                )
                            } else {
                                Color.Transparent
                            },
                            RoundedCornerShape(12.dp)
                        )
                        .padding(
                            horizontal = 16.dp,
                            vertical = 6.dp
                        )
                ) {
                    Text(
                        text = "🎬 GIFs & Memes",
                        fontWeight = if (
                            currentTab == PickerTab.GIFS_MEMES
                        ) {
                            FontWeight.Bold
                        } else {
                            FontWeight.Normal
                        },
                        color = if (
                            currentTab == PickerTab.GIFS_MEMES
                        ) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            contentColor
                        },
                        fontSize = 13.sp
                    )
                }
            }

            // ====================================================
            // EMOJI TAB
            // ====================================================

            if (currentTab == PickerTab.EMOJIS) {

                // Search
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = {
                        searchQuery = it
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    placeholder = {
                        Text(
                            text = "Search emoji...",
                            fontSize = 13.sp
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.Gray.copy(alpha = 0.35f)
                    )
                )

                // Category selector
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(
                            rememberScrollState()
                        ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    EmojiCategory.entries.forEach { category ->

                        val isSelected =
                            category == selectedCategory

                        val chipBg = if (isSelected) {
                            MaterialTheme.colorScheme.primary.copy(
                                alpha = if (isDark) 0.25f else 0.15f
                            )
                        } else {
                            Color.Transparent
                        }

                        Box(
                            modifier = Modifier
                                .clickable {
                                    selectedCategory = category
                                    searchQuery = ""
                                }
                                .background(
                                    chipBg,
                                    RoundedCornerShape(16.dp)
                                )
                                .padding(
                                    horizontal = 10.dp,
                                    vertical = 6.dp
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = category.icon,
                                    fontSize = 14.sp
                                )

                                Spacer(
                                    modifier = Modifier.width(4.dp)
                                )

                                Text(
                                    text = category.label,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) {
                                        FontWeight.Bold
                                    } else {
                                        FontWeight.Normal
                                    },
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        contentColor
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(
                    modifier = Modifier.height(8.dp)
                )

                // Emoji grid
                LazyVerticalGrid(
                    columns = GridCells.Fixed(8),
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = displayedEmojis,
                        key = { emoji -> emoji }
                    ) { emoji ->

                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clickable {
                                    onEmojiSelected(emoji)

                                    // Update Recent
                                    recentEmojis.remove(emoji)
                                    recentEmojis.add(0, emoji)

                                    // Keep Recent bounded
                                    while (recentEmojis.size > 50) {
                                        recentEmojis.removeAt(
                                            recentEmojis.lastIndex
                                        )
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = emoji,
                                fontSize = 22.sp
                            )
                        }
                    }
                }

                if (displayedEmojis.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No emojis found",
                            color = Color.Gray,
                            fontSize = 13.sp
                        )
                    }
                }

            } else {

                // =================================================
                // GIF & MEME TAB
                // =================================================

                Column(
                    modifier = Modifier.weight(1f)
                ) {

                    if (onPickCustomGif != null) {

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onPickCustomGif()
                                }
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(
                                        alpha = 0.12f
                                    ),
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "📂 Select GIF / Meme from Device (SAF)",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(
                            modifier = Modifier.height(10.dp)
                        )
                    }

                    Text(
                        text = "Curated Presets (Privacy Safe)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(CURATED_GIF_PRESETS) { preset ->

                            Box(
                                modifier = Modifier
                                    .clickable {
                                        onGifSelected?.invoke(preset)
                                    }
                                    .background(
                                        if (isDark) {
                                            Color(0xFF282828)
                                        } else {
                                            Color(0xFFE8E8E8)
                                        },
                                        RoundedCornerShape(10.dp)
                                    )
                                    .padding(
                                        vertical = 12.dp,
                                        horizontal = 8.dp
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = preset,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = contentColor
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}