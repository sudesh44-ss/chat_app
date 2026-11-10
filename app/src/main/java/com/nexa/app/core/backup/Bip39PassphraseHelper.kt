package com.nexa.app.core.backup

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

/**
 * BIP-39 compliant 12-word mnemonic phrase generator and validator.
 * Provides 128-bit client-side entropy with a 4-bit SHA-256 checksum (132 bits = 12 x 11-bit words).
 */
object Bip39PassphraseHelper {

    private val secureRandom = SecureRandom()

    /**
     * Generates a new 12-word BIP-39 recovery mnemonic phrase.
     */
    fun generateMnemonic(): String {
        val entropy = ByteArray(16) // 128 bits
        secureRandom.nextBytes(entropy)
        return entropyToMnemonic(entropy)
    }

    /**
     * Alias for generateMnemonic() for backward compatibility.
     */
    fun generate12WordPassphrase(): String = generateMnemonic()

    /**
     * Alias for validateMnemonic() for backward compatibility.
     */
    fun isValidPassphrase(mnemonic: String): Boolean = validateMnemonic(mnemonic)

    /**
     * Standard BIP-39 PBKDF2-HMAC-SHA512 seed derivation (2048 iterations, 512 bits / 64 bytes).
     */
    fun toSeed(mnemonic: String, passphrase: String = ""): ByteArray {
        val normalized = normalize(mnemonic)
        val salt = ("mnemonic$passphrase").toByteArray(Charsets.UTF_8)
        val spec = javax.crypto.spec.PBEKeySpec(normalized.toCharArray(), salt, 2048, 512)
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        return factory.generateSecret(spec).encoded
    }

    /**
     * Converts 16 bytes of entropy into 12 BIP-39 words.
     */
    fun entropyToMnemonic(entropy: ByteArray): String {
        require(entropy.size == 16) { "Entropy must be exactly 16 bytes for 12 words" }

        // SHA-256 checksum: first 4 bits of hash
        val hash = MessageDigest.getInstance("SHA-256").digest(entropy)
        val checksumByte = hash[0].toInt() and 0xFF

        // Build 132-bit bitstring
        val bits = BooleanArray(132)
        for (i in 0 until 128) {
            val byteIndex = i / 8
            val bitIndex = 7 - (i % 8)
            bits[i] = (((entropy[byteIndex].toInt() and 0xFF) ushr bitIndex) and 1) == 1
        }
        // Append 4 checksum bits
        for (i in 0 until 4) {
            val bitIndex = 7 - i
            bits[128 + i] = ((checksumByte ushr bitIndex) and 1) == 1
        }

        // Split into 12 words (11 bits each)
        val words = ArrayList<String>(12)
        for (i in 0 until 12) {
            var wordIndex = 0
            for (j in 0 until 11) {
                wordIndex = wordIndex shl 1
                if (bits[i * 11 + j]) {
                    wordIndex = wordIndex or 1
                }
            }
            words.add(WORD_LIST[wordIndex])
        }

        return words.joinToString(" ")
    }

    /**
     * Validates whether a given mnemonic string is a valid BIP-39 12-word phrase.
     */
    fun validateMnemonic(mnemonic: String): Boolean {
        val words = normalize(mnemonic).split(" ").filter { it.isNotBlank() }
        if (words.size != 12) return false

        val wordIndices = IntArray(12)
        for (i in 0 until 12) {
            val idx = WORD_LIST_INDEX_MAP[words[i]] ?: return false
            wordIndices[i] = idx
        }

        // Convert indices to 132 bits
        val bits = BooleanArray(132)
        for (i in 0 until 12) {
            val idx = wordIndices[i]
            for (j in 0 until 11) {
                val bitPos = 10 - j
                bits[i * 11 + j] = ((idx ushr bitPos) and 1) == 1
            }
        }

        // Reconstruct 16-byte entropy
        val entropy = ByteArray(16)
        for (i in 0 until 128) {
            if (bits[i]) {
                val byteIndex = i / 8
                val bitIndex = 7 - (i % 8)
                entropy[byteIndex] = (entropy[byteIndex].toInt() or (1 shl bitIndex)).toByte()
            }
        }

        // Validate 4-bit checksum
        val hash = MessageDigest.getInstance("SHA-256").digest(entropy)
        val expectedChecksumByte = hash[0].toInt() and 0xFF
        for (i in 0 until 4) {
            val bitIndex = 7 - i
            val expectedBit = ((expectedChecksumByte ushr bitIndex) and 1) == 1
            if (bits[128 + i] != expectedBit) {
                return false
            }
        }

        return true
    }

    /**
     * Normalizes mnemonic phrase: trims, lowercases, removes extraneous spaces.
     */
    fun normalize(mnemonic: String): String {
        return mnemonic.trim()
            .lowercase(Locale.ROOT)
            .split("\\s+".toRegex())
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    }

    /**
     * Splits normalized mnemonic into a list of 12 distinct words.
     */
    fun getWords(mnemonic: String): List<String> {
        return normalize(mnemonic).split(" ").filter { it.isNotEmpty() }
    }

    // BIP-39 English Wordlist (2048 words)
    val WORD_LIST: List<String> by lazy {
        WORD_LIST_RAW.trim().split("\\s+".toRegex())
    }

    private val WORD_LIST_INDEX_MAP: Map<String, Int> by lazy {
        WORD_LIST.mapIndexed { index, word -> word to index }.toMap()
    }

    private const val WORD_LIST_RAW = """
abandon ability able about above absent absorb abstract absurd abuse
access accident account accuse achieve acid acoustic acquire across act
action actor actress actual adapt add addict address adjust admit
adult advance advice aerobic affair afford afraid again age agent
agree ahead aim air airport aisle alarm album alcohol alert
alien all alley allow almost alone alpha already also alter
always amateur amaze among amount amuse analyst anchor ancient anger
angle angry animal ankle announce annual another answer antenna antique
anxiety any apart apology appear apple approve april arch arctic
area arena argue arm armed armor army around arrange arrest
arrive arrow art artefact artist artwork ask aspect assault asset
assist assume asthma athlete atom attack attend attitude attract auction
audit august aunt author auto autumn average avocado avoid awake
aware away awesome awful awkward axis baby bachelor bacon badge
bag balance balcony ball bamboo banana banner bar barely bargain
barrel base basic basket battle beach bean beauty because become
beef before begin behave behind believe below belt bench benefit
best betray better between beyond bicycle bid bike bind biology
bird birth bitter black blade blame blanket blast bleak bless
blind blood blossom blouse blue blur blush board boat body
boil bomb bone bonus book boost border boring borrow boss
bottom bounce box boy bracket brain brand brass brave bread
breeze brick bridge brief bright bring brisk broccoli broken bronze
broom brother brown brush bubble buddy budget buffalo build bulb
bulk bullet bundle bunker burden burger burst bus business busy
butter buyer buzz cabbage cabin cable cactus cage cake call
calm camera camp can canal cancel candy candle canon canvas
canyon capable capital captain car carbon card care cargo carpet
carry cart case cash casual cat catalog catch category cattle
caught cause caution cave ceiling celery cement census century cereal
certain chair chalk champion change chaos chapter charge chase chat
cheap check cheese chef cherry chest chicken chief child chimney
choice choose chronic chuckle chunk churn cigar cinnamon circle citizen
city civil claim clap clarify claw clay clean clerk clever
click client cliff climb clinic clip clock clog close cloth
cloud clown club clump cluster clutch coach coast coconut code
coffee coil coin collect color column combine come comfort comic
common company concert conduct confirm congress connect consider control convince
cook cool copper copy coral core corn correct cost cotton
couch country couple course cousin cover coyote crack cradle craft
cram crane crash crater crawl crazy cream credit creek crew
cricket crime crisp critic crop cross crouch crowd crucial cruel
cruise crumble crunch crush cry crystal cube culture cup cupboard
curtain curve cushion custody custom cute cycle dad damage damp
dance danger daring dash daughter dawn day deal debate debris
decade december decide decline decorate decrease deer defense define defy
degree delay deliver demand demise denial dentist deposit depth deputy
derive describe desert design desk despair destroy detail detect develop
device devote diagram dial diamond diary dice diesel diet differ
digital dignity dilemma dinner dinosaur direct dirt disagree discover disease
dish dismiss disorder display distance divert divide divorce dizzy dock
doctor document dog doll dolphin domain donate donkey donor door
dose double dove draft dragon drama drastic draw dream dress
drift drill drink drip drive drop drum dry duck dumb
dune during dust dutch duty dwarf dynamic eager eagle early
earn earth easily east easy echo ecology economy edge edit
educate effort egg eight either elbow elder electric elegant element
elephant elevator elite else embark embody embrace emerge emotion employ
empower empty enable enact end endless endorse enemy energy enforce
engage engine enhance enjoy enlist enough enrich enroll ensure enter
entire entry episode equal equip era erase erect error erupt
escape essay essence estate eternal ethics evidence evil evoke evolve
exact example excess exchange excite exclude excuse execute exercise exhaust
exhibit exile exist exit exotic expand expect expire explain expose
express extend extra eye eyebrow fabric face faculty fade faint
faith fall false fame family famous fan fancy fantasy farm
fashion fat fatal father fatigue fault favorite feature february federal
fee feed feel female fence festival fetch fever few fiber
fiction field figure file film filter final find fine finger
finish fire firm first fiscal fish fit fitness fix flag
flame flash flat flavor flee flight float flock floor flower
fluid flush fly foam focus fog foil fold follow food
foot force forest forget fork fortune forum forward fossil foster
found fragile frame frequent fresh friend fringe frog front frost
frown frozen fruit fuel fun funny furnace fury future gadget
gain galaxy gallery game gap garage garbage garden garlic garment
gas gasp gate gather gauge gaze general genius genre gentle
genuine gesture ghost giant gift giggle ginger giraffe girl give
glad glance glare glass glide glimpse globe gloom glory glove
glow glue goat goddess gold good goose gorilla gospel gossip
govern gown grab grace grain grant grape grass gravity gray
great green grid grief grind grip groan grocery ground group
grow grunt guard guess guide guilt guitar gun gym habit
hair half hammer hamster hand happy harbor hard harsh harvest
hat have hawk hazard head health heart heavy hedgehog height
hello helmet help hen hero hidden high hill hint hip
hire history hobby hockey hold hole holiday hollow home honey
hood hope horn horror horse hospital host hotel hour hover
hub huge human humble humor hundred hungry hunt hurdle hurry
hurt husband hybrid ice icon idea identify idle ignore ill
illegal illness image imitate immense immune impact impose improve impulse
inch include income increase index indicate indoor industry infant inflict
inform inhale inherit initial inject injury inmate inner innocent input
inquiry insane insect inside inspire install intact interest into invest
invite involve iron island isolate issue item ivory jacket jaguar
jar jazz jealous jeans jelly jewel job join joke journey
joy judge juice jump jungle junior junk just kangaroo keen
keep ketchup key kick kid kidney kind kingdom kiss kit
kitchen kite kitten kiwi knee knife knock know lab label
labor ladder lady lake lamp language laptop large later latin
laugh laundry lava law lawn lawsuit layer lazy leader leaf
learn leave lecture left leg legal legend leisure lemon lend
length leopard lesson letter level liar liberty library license life
lift light like limb limit link lion liquid list little
live lizard load loan lobster local lock logic lonely long
loop lottery loud lounge love loyal lucky luggage lumber lunar
lunch luxury lyrics machine mad magic magnet maid mail main
major make mammal man manage mandate mango manifest mansion manual
maple marble march margin marine market marriage mask mass master
match material math matrix matter maximum maze meadow mean measure
meat mechanic medal media melody melt member memory mention menu
mercy merge merit merry mesh message metal method middle midnight
milk mimic mind minimum minor minute miracle mirror misery miss
mistake mix mixed mixture mobilize model modify module moist moment
monitor monkey monster month moon moral more morning mosquito mother
motion motor mountain mouse move movie much muffin mule multiply
muscle museum mushroom music must mutual myself mystery myth naive
name napkin narrow nasty nation nature near neck need needle
negative neglect neither nephew nerve nest net network neutral never
news next nice niche nickel niece night nine nitrogen no
noble noise nominee noodle normal north nose notable note nothing
notice novel now nuclear number nurse nut oak obey object
oblige obscure observe obtain obvious ocean october odor off offer
office officer offline often oil okay old olive olympic omit
once one onion online only onset open opera opinion oppose
option orange orbit orchard order ordinary organ orient original orphan
ostrich other outdoor outer output outside oval oven over overcome
overdo owe owl owner oxygen oyster ozone pact paddle page
pair palace palm panda panel panic panther paper parade parent
park parrot party pass patch path patient patrol pattern pause
pave payment peace peanut pear peasant pelican pen penalty pencil
people pepper perfect permit person pet phone photo phrase physical
piano picnic picture piece pig pigeon pill pilot pin pine
pink pipe pistol pitch pizza place planet plastic plate play
please pledge pluck plug plunge poem poet point polar pole
police pond pony pool popular porch position possible post potato
pottery poverty powder power practice praise predict prefer prepare present
pretty prevent price pride primary print priority prison private prize
problem process produce profit program project promote proof property prosper
protect proud provide public pull pulp pulse pumpkin punch puppy
purchase purity purpose purse push put puzzle pyramid quality quantum
quarter question quick quit quiz quote rabbit raccoon race rack
radar radio rail rain raise rally ramp ranch random range
rapid rare rate rather raven raw razor reach ready real
reason rebel receipt receive recipe reckon record recruit recycle reduce
reflect reform refugee refuse region regret regular release relief rely
remain remember remind remove render renew rent reopen repair repeat
replace report require rescue resemble resist resource response result retire
retreat return reunion reveal review reward rhythm rib ribbon rice
rich ride ridge rifle right rigid ring riot ripple risk
ritual rival river road roar roast rob robot robust rocket
romance roof rookie room rose rotate rough round route row
royal rubber rude rug rule run runway rural sad saddle
sadness safe sail salad salmon salon salt salute same sample
sand satisfy sauce sausage save save say scale scan scare
scatter scene scheme school science scissors scorpion scout scrap screen
script scrub sea search season seat second secret section security
seed seek segment select sell seminar senior sense sentence series
service session settle setup seven shadow shaft shallow share shed
shell sheriff shield shift shine ship shiver shock shoe shoot
shop short shoulder shove show shrub shrug shuffle shy sibling
sick side siege sight sign silent silk silly silver similar
simple since sing siren sister situate six size skate sketch
ski skill skin skirt skull slab slam sleep slender slice
slide slight slim slogan slot slow slush small smart smile
smoke smooth snack snake snap sniff snow soap soccer social
sock soda soft solar soldier solid solve someone song soon
sorry sort soul sound soup source south space spare spatial
spawn speak special speed spell spend sphere spice spider spike
spin spirit split spoil sponsor spoon sport spot spray spread
spring spy square squeeze squirrel stable stadium staff stage stairs
stamp stand start state stay steak steel stem step stereo
stick stiff still sting stock stomach stone stool store storm
story stove strategy street strike stroll strong struggle student stuff
stumble style subject submit subway success such sudden suffer sugar
suggest suit summer sun sunny sunset super supply supreme sure
surface surge surprise surround survey suspect sustain swallow swamp swap
swarm swear sweet swift swim swing switch sword symbol symptom
syrup system table tackle tag tail talent talk tank tape
target task taste tattoo taxi teach team tell ten tenant
tennis term test text thank that theme then theory there
they thing this thought three thrive throw thumb thunder ticket
tide tiger tilt timber time tiny tip tired tissue title
toast tobacco today toddler toe together toilet token tomato tomorrow
tone tongue tonight tool tooth top topic topple torch tornado
tortoise toss total tourist toward tower town toy trace track
trade traffic train transfer trap trash travel tray treat tree
trend trial tribe trick trigger trim trip trophy trouble truck
true truly trumpet trust truth try tube tuition tumble tuna
tunnel turkey turn turtle twelve twenty twice twin twist two
type typical ugly umbrella unable unaware uncle uncover under undo
unfair unfold unhappy uniform unique unit universe unknown unlock until
unusual unveil update upgrade uphold upon upper upset urban urge
usage use used useful useless usual utility vacant vacuum vague
valid valley valve van vanish vapor variable vary vast vault
vector vegetable vehicle velvet vendor venture venue verb verify version
very vessel veteran viable vibrate vicious victory view village vintage
violin virtual virus visit visual vital vivid vocal voice void
volcano volume vote vow voyage wage wagon wait walk wall
walnut want war warm warrior wash wasp waste water wave
way wealth weapon wear weasel weather web wedding week weight
welcome west wet whale what wheat wheel when where whip
whisper wide width wife wild will win window wine wing
wink winner winter wire wisdom wise wish witness wolf woman
wonder wood wool word work world worry worth wrap wreck
wrestle wrist write wrong yard year yellow you young youth
zebra zero zone zoo
"""
}
