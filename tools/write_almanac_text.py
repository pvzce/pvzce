#!/usr/bin/env python3
"""Write the almanac's text into the built-in language files.

The almanac shows, for every plant, zombie and resource, the original game's *card line*
(``.desc``) and its *bio* (``.flavor``) verbatim. Both are text, so both live in
``assets/pvzce/lang/<locale>.json`` under the content key the rest of the game already uses:

    plant.pvzce.pea_shooter          the name
    plant.pvzce.pea_shooter.desc     the line printed on the card face
    plant.pvzce.pea_shooter.flavor   the italic bio under it

Keys are ``<registry>.<namespace>.<path>`` - see ``RegistryCategories`` on the Java side, which
is what looks them up.

Only the two locales below are written. ``en_us`` gets the English text as well, because the
English is the original's own wording and costs nothing extra to ship; a locale that is not
listed simply keeps whatever the file has (the almanac falls back to the id's path).

Numbers are NOT written here: sun cost, recharge, health, speed and bite damage are read from
the plants' and zombies' own definitions at draw time, so the almanac can never claim a value
the simulation does not use.

Run from the repository root:

    python3 tools/write_almanac_text.py
"""
import json
import os
import sys

LANG = "pvzce-game/src/main/resources/assets/pvzce/lang"

# --------------------------------------------------------------------------------------------
# Plants. `desc` is the card line, `flavor` the bio, both as shipped in PvZ1. The line is a
# description of what the plant does, so it is *not* re-worded to match this project's numbers.
# --------------------------------------------------------------------------------------------
PLANTS = {
    "pea_shooter": (
        "Peashooters are your first line of defense. They shoot peas at attacking zombies.",
        "How can a single plant grow and shoot so many peas so quickly? Peashooter says, "
        "\"Hard work, commitment, and a healthy, well-balanced breakfast of sunlight and "
        "high-fiber carbon dioxide make it all possible.\"",
        "豌豆射手是您的第一道防线。它们会向进攻的僵尸射出豌豆。",
        "一株植物怎么能长得这么快、射出这么多豌豆？豌豆射手说：“努力工作、全心投入，"
        "再加上一顿营养均衡的早餐——阳光和高纤维二氧化碳——就能做到这一切。”",
    ),
    "sunflower": (
        "Sunflowers are essential for you to produce extra sun. Try planting as many as you can!",
        "Sunflower can't resist bouncing to the beat. Which beat is that? Why, the life-giving "
        "jazzy rhythm of the Earth itself, thumping at a frequency only Sunflower can hear.",
        "向日葵是您额外获取阳光的关键。尽量多种一些吧！",
        "向日葵总是忍不住跟着节拍摇摆。什么节拍？当然是大地本身那赋予生命的爵士节奏——"
        "它跳动的频率只有向日葵听得见。",
    ),
    "cherry_bomb": (
        "Cherry Bombs can blow up all zombies in an area. They have a short fuse so plant them "
        "near zombies.",
        "\"I wanna explode,\" says Cherry #1. \"No, let's detonate instead!\" says his brother, "
        "Cherry #2. After intense consultation they agree to explodonate.",
        "樱桃炸弹能炸飞一片区域内的所有僵尸。它们的引信很短，所以要种在僵尸附近。",
        "“我想爆炸。”樱桃一号说。“不，我们引爆吧！”他的兄弟樱桃二号说。"
        "经过激烈的讨论，他们一致同意：爆——炸。",
    ),
    "wall_nut": (
        "Wall-nuts have hard shells which you can use to protect your other plants.",
        "\"People wonder how I feel about getting constantly chewed on by zombies,\" says "
        "Wall-nut. \"What they don't realize is that with my limited senses all I can feel is a "
        "kind of tingling, like a relaxing back rub.\"",
        "坚果墙有着坚硬的外壳，您可以靠它保护其他植物。",
        "“大家都想知道，整天被僵尸啃来啃去我是什么感觉，”坚果墙说，"
        "“他们不知道的是，以我那点可怜的知觉，能感受到的只是一种酥麻，"
        "就像一次放松的背部按摩。”",
    ),
    "potato_mine": (
        "Potato Mines pack a powerful punch, but they need a while to arm themselves. You should "
        "plant them ahead of zombies. They will explode on contact.",
        "Some folks say Potato Mine is lazy, that he leaves everything to the last minute. Potato "
        "Mine says nothing. He's too busy thinking about his investment strategy.",
        "土豆雷威力巨大，但需要一点时间才能武装自己。您应该把它们种在僵尸前面。"
        "它们会在接触时爆炸。",
        "有人说土豆雷很懒，什么事都拖到最后一刻。土豆雷什么也没说——"
        "他正忙着思考自己的投资策略。",
    ),
    "snow_pea": (
        "Snow Peas shoot frozen peas that damage and slow the enemy.",
        "Folks often tell Snow Pea how \"cool\" he is, or exhort him to \"chill out.\" They tell "
        "him to \"stay frosty.\" Snow Pea just rolls his eyes. He's heard 'em all.",
        "寒冰射手射出冰冻的豌豆，既能伤害敌人，又能让它减速。",
        "大家总说寒冰射手有多“酷”，或者劝他“冷静点”。他们叫他“保持高冷”。"
        "寒冰射手只是翻个白眼——这些梗他全听过了。",
    ),
    "chomper": (
        "Chompers can devour a zombie whole, but they are vulnerable while chewing.",
        "Chomper almost got a gig doing stunts for The Little Shop of Horrors but it fell through "
        "when his agent demanded too much on the front end. Chomper's not resentful, though. He "
        "says it's just part of the business.",
        "大嘴花能一口吞下整只僵尸，但在咀嚼的时候很脆弱。",
        "大嘴花差点接到《恐怖小店》的特技替身工作，但因为他的经纪人开口要价太高而告吹。"
        "不过大嘴花并不记恨。他说，这就是这一行。",
    ),
    "repeater": (
        "Repeaters fire two peas at a time.",
        "Repeater is fierce. He's from the streets. He doesn't take attitude from anybody, plant "
        "or zombie, and he shoots peas to keep people at a distance. Secretly, though, Repeater "
        "yearns for love.",
        "双发射手一次射出两颗豌豆。",
        "双发射手很凶。他是街头出身，无论对方是植物还是僵尸，他都不吃那一套，"
        "他用豌豆把所有人挡在安全距离之外。不过私底下，双发射手渴望被爱。",
    ),
    "puff_shroom": (
        "Puff-shrooms are cheap, but can only fire a short distance.",
        "\"I only recently became aware of the existence of zombies,\" says Puff-shroom. \"Like "
        "many fungi, I'd just assumed they were fairy tales or movie monsters. This whole "
        "experience has been a huge eye-opener for me.\"",
        "小喷菇很便宜，但射程很短。",
        "“我直到最近才知道僵尸是真实存在的，”小喷菇说，"
        "“和许多真菌一样，我一直以为它们只是童话或电影里的怪物。"
        "这段经历真是让我大开眼界。”",
    ),
    "sun_shroom": (
        "Sun-shrooms give small sun at first and normal sun later.",
        "Sun-shroom hates sun. He hates it so much that when it builds up in his system, he spits "
        "it out as fast as he can. He just won't abide it. To him, sun is crass.",
        "阳光菇一开始只能产出小阳光，之后会产出普通阳光。",
        "阳光菇讨厌阳光。他讨厌到一旦阳光在体内积聚，就会尽快把它吐出来。"
        "他就是受不了。对他来说，阳光太俗气了。",
    ),
    "fume_shroom": (
        "Fume-shrooms shoot fumes that can pass through screen doors.",
        "\"I was in a dead-end job producing yeast spores for a bakery,\" says Fume-shroom. "
        "\"Then Puff-shroom, bless 'im, told me about this great opportunity blasting zombies. "
        "Now I really feel like I'm making a difference.\"",
        "大喷菇喷出的孢子雾可以穿过纱门。",
        "“我以前在一家面包店做酵母孢子，没什么前途，”大喷菇说，"
        "“后来小喷菇，老天保佑他，告诉我有个轰僵尸的好差事。"
        "现在我真觉得自己在做有意义的事。”",
    ),
    "grave_buster": (
        "Plant Grave Busters on graves to remove the graves.",
        "Despite Grave Buster's fearsome appearance, he wants everyone to know that he loves "
        "kittens and spends his off hours volunteering at a local zombie rehabilitation center. "
        "\"It's just the right thing to do,\" he says.",
        "把墓碑破坏者种在墓碑上，就能清除墓碑。",
        "尽管墓碑破坏者长相吓人，但他想让大家知道，他喜欢小猫，"
        "业余时间还在当地的僵尸康复中心做志愿者。“这是应该做的。”他说。",
    ),
    "hypno_shroom": (
        "When eaten, Hypno-shrooms will make a zombie turn around and fight for you.",
        "\"Zombies are our friends,\" asserts Hypno-shroom. \"They're badly misunderstood "
        "creatures who play a valuable role in our ecology. We can and should do more to bring "
        "them round to our way of thinking.\"",
        "被吃掉后，魅惑菇会让僵尸掉头为你而战。",
        "“僵尸是我们的朋友，”魅惑菇断言，"
        "“它们是被严重误解的生物，在我们的生态中扮演着重要的角色。"
        "我们可以、也应该做更多的事，让它们接受我们的想法。”",
    ),
    "scaredy_shroom": (
        "Scaredy-shrooms are long-ranged shooters that hide when enemies get near them.",
        "\"Who's there?\" whispers Scaredy-shroom, voice barely audible. \"Go away. I don't want "
        "to see anybody. Unless it's the man from the circus.\"",
        "胆小菇是远程射手，但敌人靠近时就会躲起来。",
        "“谁在那儿？”胆小菇低声问，声音小得几乎听不见。"
        "“走开。我不想见任何人。除非是马戏团来的人。”",
    ),
    "ice_shroom": (
        "Ice-shrooms temporarily immobilize all zombies on the screen.",
        "Ice-shroom frowns, not because he's unhappy or because he disapproves, but because of a "
        "childhood injury that left his facial nerves paralyzed.",
        "寒冰菇能暂时冻住屏幕上所有的僵尸。",
        "寒冰菇皱着眉，不是因为他不高兴，也不是因为他反对什么，"
        "而是因为童年的一次受伤让他的面部神经瘫痪了。",
    ),
    "doom_shroom": (
        "Doom-shrooms destroy everything in a large area and leave a crater that can't be planted "
        "on.",
        "\"You're lucky I'm on your side,\" says Doom-shroom. \"I could destroy everything you "
        "hold dear. It wouldn't be hard.\"",
        "毁灭菇会摧毁一大片区域内的所有东西，并留下一个无法种植的弹坑。",
        "“你该庆幸我站在你这边，”毁灭菇说，"
        "“我能毁掉你珍视的一切。这一点都不难。”",
    ),
    "lily_pad": (
        "Lily pads let you plant non-aquatic plants on top of them.",
        "Lily Pad never complains. Lily Pad never wants to know what's going on. Put a plant on "
        "top of Lily Pad, he won't say a thing. Does he have startling opinions or shocking "
        "secrets? Nobody knows. Lily Pad keeps it all inside.",
        "莲叶能让您把非水生植物种在它上面。",
        "莲叶从不抱怨。莲叶从不想知道发生了什么。把一株植物放在莲叶上面，他一声不吭。"
        "他是否藏着惊人的观点或骇人的秘密？没人知道。莲叶把一切都藏在心里。",
    ),
    "squash": (
        "Squashes will smash the first zombie that gets close to it.",
        "\"I'm ready!\" yells Squash. \"Let's do it! Put me in! There's nobody better! I'm your "
        "guy! C'mon! Whaddya waiting for? I need this!\"",
        "窝瓜会砸扁第一只靠近它的僵尸。",
        "“我准备好了！”窝瓜喊道，“来吧！让我上！没人比我更合适！我就是你要的人！"
        "快点！你还在等什么？我需要这个机会！”",
    ),
    "threepeater": (
        "Threepeaters shoot peas in three lanes.",
        "Threepeater likes reading, backgammon and long periods of immobility in the park. "
        "Threepeater enjoys going to shows, particularly modern jazz. \"I'm just looking for that "
        "special someone,\" he says. Threepeater's favorite number is 5.",
        "三线射手可以同时向三条路线发射豌豆。",
        "三线射手喜欢读书、玩双陆棋，还喜欢在公园里长时间一动不动。"
        "三线射手爱看演出，尤其是现代爵士。“我只是在寻找那个特别的人。”他说。"
        "三线射手最喜欢的数字是 5。",
    ),
    "tangle_kelp": (
        "Tangle Kelp are aquatic plants that pull the first zombie that nears them underwater.",
        "\"I'm totally invisible,\" Tangle Kelp thinks to himself. \"I'll hide here just below the "
        "surface and nobody will see me.\" His friends tell him they can see him perfectly well, "
        "but he'll never change.",
        "缠绕水草是水生植物，会把第一只靠近它的僵尸拖入水下。",
        "“我完全隐形了，”缠绕水草心想，"
        "“我就藏在水面下，谁也不会看见我。”朋友们告诉他，他们看得一清二楚，"
        "但他永远不会改。",
    ),
    "jalapeno": (
        "Jalapenos destroy an entire lane of zombies.",
        "\"NNNNNGGGGG!!!!!!!!\" Jalapeno says. He's not going to explode, not this time. But soon. "
        "Oh, so soon. It's close. He knows it, he can feel it, his whole life's been leading up to "
        "this moment.",
        "火爆辣椒能消灭一整条路线上的僵尸。",
        "“嗯嗯嗯嗯嗯!!!!!!!!”火爆辣椒说。他不会爆炸，这一次不会。但快了。"
        "哦，就快了。很近了。他知道，他能感觉到，他的一生都在为这一刻做准备。",
    ),
    "cactus": (
        "Cactuses shoot spikes that can hit both ground and air targets.",
        "She's prickly, sure, but Cactus' spikes belie a spongy heart filled with love and "
        "goodwill. She just wants to hug and be hugged. Most folks can't hang with that, but "
        "Cactus doesn't mind. She's been seeing an armadillo for a while and it really seems to be "
        "working out.",
        "仙人掌射出的尖刺既能打地面目标，也能打空中目标。",
        "她确实浑身是刺，但仙人掌的尖刺之下是一颗充满爱与善意、软绵绵的心。"
        "她只是想拥抱，也想被拥抱。大多数人受不了这一点，但仙人掌不介意。"
        "她和一只犰狳交往了一段时间，看起来进展顺利。",
    ),
    "split_pea": (
        "Split Peas shoot peas forward and backwards.",
        "Yeah, I'm a Gemini,\" says Split Pea. \"I know, big surprise. But having two heads -- or "
        "really, one head with a large head-like growth on the back -- pays off big in my line of "
        "work.\"",
        "分裂豌豆可以向前和向后发射豌豆。",
        "“没错，我是双子座，”分裂豌豆说，"
        "“我知道，很意外吧。但有两个脑袋——或者说，一个脑袋加上背后长出的一个很像脑袋的东西"
        "——在我这一行里收益很大。”",
    ),
    "coffee_bean": (
        "Use Coffee Beans to wake up sleeping mushrooms.",
        "\"Hey, guys, hey!\" says Coffee Bean. \"Hey! What's up? Who's that? Hey! Didja see that "
        "thing? What thing? Whoa! Lions!\" Yep, Coffee Bean sure does get excited.",
        "用咖啡豆唤醒沉睡的蘑菇。",
        "“嘿，伙计们，嘿！”咖啡豆说，“嘿！怎么样？那是谁？嘿！你看见那东西了吗？"
        "什么东西？哇！狮子！”没错，咖啡豆就是这么大惊小怪。",
    ),
    "flower_pot": (
        "Flower Pots let you plant on the roof.",
        "\"I'm a pot for planting. Yet I'm also a plant. HAS YOUR MIND EXPLODED YET?\"",
        "花盆能让您在屋顶上种植。",
        "“我是一个用来种东西的盆。但我同时也是一株植物。你的脑子炸了吗？”",
    ),
    "kernel_pult": (
        "Kernel-pults fling corn kernels and butter at zombies.",
        "Kernel-pult is the eldest of the Pult brothers. Of the three of them, Kernel is the only "
        "one who consistently remembers the others' birthdays. He bugs them about it a little, "
        "too.",
        "玉米投手会向僵尸投掷玉米粒和黄油。",
        "玉米投手是投手三兄弟中的老大。三兄弟里，只有玉米每次都记得另外两位的生日。"
        "他还会拿这件事小小地念叨他们。",
    ),
    "cabbage_pult": (
        "Cabbage-pults hurl cabbages at the enemy.",
        "Cabbage-pult is okay with launching cabbages at zombies. It's what he's paid for, after "
        "all, and he's good at it. He just doesn't understand how the zombies get up on the roof "
        "in the first place.",
        "卷心菜投手会向敌人投掷卷心菜。",
        "卷心菜投手并不介意朝僵尸扔卷心菜。毕竟这就是他的薪水来源，而且他很擅长。"
        "他只是想不通：僵尸到底是怎么爬到屋顶上去的。",
    ),
    "marigold": (
        "Marigolds give you silver and gold coins.",
        "Marigold spends a lot of time deciding whether to spit out a silver coin or a gold one. "
        "She thinks about it, weighs the angles. She does solid research and keeps up with current "
        "publications. That's how winners stay ahead.",
        "金盏花会为您产出银币和金币。",
        "金盏花会花很多时间决定该吐出银币还是金币。她反复思考，权衡各种角度。"
        "她做扎实的研究，紧跟最新的文献。赢家就是这样保持领先的。",
    ),
    "melon_pult": (
        "Melon-pults do heavy damage to groups of zombies.",
        "There's no false modesty with Melon-pult. \"Sun-for-damage, I deliver the biggest punch "
        "on the lawn,\" he says. \"I'm not bragging. Run the numbers. You'll see.\"",
        "西瓜投手能对成群的僵尸造成巨大伤害。",
        "西瓜投手从不假谦虚。“按阳光换伤害来算，草坪上我的拳头最重，”他说，"
        "“我不是在吹牛。算算数字，你就知道了。”",
    ),
    "gatling_pea": (
        "Gatling Peas shoot four peas at a time.",
        "Gatling Pea's parents were concerned when he announced his intention to join the "
        "military. \"But honey, it's so dangerous!\" they said in unison. Gatling Pea refused to "
        "budge. \"Life is dangerous,\" he replied, eyes glinting with steely conviction.",
        "机枪射手一次射出四颗豌豆。",
        "机枪射手宣布要参军时，他的父母很担心。“可是宝贝，那太危险了！”他们异口同声地说。"
        "机枪射手寸步不让。“生活本来就危险。”他答道，眼中闪着钢铁般的信念。",
    ),
    "winter_melon": (
        "Winter Melons do heavy damage and slow groups of zombies.",
        "Winter Melon tries to calm his nerves. He hears zombies approach. Will he make it? Will "
        "anyone make it?",
        "冰西瓜能对成群的僵尸造成巨大伤害，并让它们减速。",
        "冰西瓜努力让自己镇定下来。他听见僵尸逼近的脚步声。他能撑过去吗？"
        "还有人能撑过去吗？",
    ),
    "bowling_nut": (
        "Bowling Wall-nuts are rolled down the lane to knock zombies over.",
        "Bowling Wall-nut never asked to be a projectile. He was a perfectly good Wall-nut, "
        "minding his own business, until someone decided he would look better in a bowling "
        "shirt.",
        "保龄球坚果会被滚出去撞倒一路上的僵尸。",
        "保龄球坚果从没要求过成为一颗炮弹。他本来是一颗安分守己的坚果墙，"
        "直到有人觉得他穿保龄球衫更好看。",
    ),
}

# --------------------------------------------------------------------------------------------
# Zombies. Only the ones this project actually has; the order the almanac lists them in is the
# original's, and lives in AlmanacEntries on the Java side.
# --------------------------------------------------------------------------------------------
ZOMBIES = {
    "basic_zombie": (
        "This zombie is your standard garden-variety zombie. He has no defensive equipment or "
        "special abilities to protect himself with and is susceptible to any attack.",
        "This zombie loves brains. Can't get enough. Brains, brains, brains, day in and night out. "
        "Old and stinky brains? Rotten brains? Brains clearly past their prime? Doesn't matter. "
        "Regular zombie wants 'em.",
        "这是园子里最常见的僵尸。他没有任何防护装备，也没有什么特殊能力，"
        "对任何攻击都毫无抵抗力。",
        "这类僵尸甚爱脑袋，从来都吃不够。脑袋、脑袋、脑袋，一天到晚如此。"
        "又老又臭的脑袋？腐烂的脑袋？过了青春期的脑袋？不要紧。本僵尸从不挑食。",
    ),
    "flag_zombie": (
        "This zombie carries a red flag with a brain on it. He heralds the arrival of a large wave "
        "of zombies, of which every standard Adventure Mode level has at least one.",
        "Make no mistake, Flag Zombie loves brains. But somewhere down the line he also picked up "
        "a fascination with flags. Maybe it's because the flags always have brains on them. Hard "
        "to say.",
        "这只僵尸举着一面画着脑袋的红旗。他代表着一大波僵尸的到来——"
        "每个标准冒险模式关卡至少会有一波。",
        "别搞错了，旗帜僵尸也喜欢脑袋。但不知从什么时候起，他也迷上了旗帜——"
        "也许是因为上面总画着脑袋吧。不好说。",
    ),
    "conehead_zombie": (
        "This zombie wears an orange traffic cone on his head as protection. He has around 2.5 "
        "times as much health as a standard zombie.",
        "Conehead Zombie shuffled mindlessly forward like every other zombie. But something made "
        "him stop, made him pick up a traffic cone and stick it on his head. Oh yeah. He likes to "
        "party.",
        "这只僵尸头上扣着一个橙色的路障当防护。他的血量大约是普通僵尸的 2.5 倍。",
        "路障僵尸和其他僵尸一样，漫无目的地向前挪动。但有样东西让他停了下来，"
        "让他捡起一个路障扣在了头上。哦对了——他喜欢开派对。",
    ),
    "pole_vaulter_zombie": (
        "When Pole Vaulting Zombie enters the lawn he will jog briskly carrying a vaulting pole. "
        "As soon as he reaches a plant he will use the pole to leap over it. After that he loses "
        "the pole and his speed slows to that of a regular zombie.",
        "Some zombies take it further, aspire more, push themselves beyond the normal into "
        "greatness. That's Pole Vaulting Zombie right there. That is so him.",
        "撑杆僵尸进入草坪时会轻快地小跑，手里握着撑杆。一碰到植物，他就会用撑杆跳过去。"
        "之后他会丢掉撑杆，速度降到普通僵尸的水平。",
        "有些僵尸走得更远，志向更高，超越平庸，成就卓越。撑杆僵尸就是这样。"
        "他就是这么个僵尸。",
    ),
    "buckethead_zombie": (
        "This zombie is protected by the metal bucket he wears on his head, which makes him very "
        "resistant to damage. He has around five times the health of a standard zombie.",
        "Buckethead Zombie always wore a bucket. Part of it was to assert his uniqueness in an "
        "uncaring world. Mostly he just forgot it was there in the first place.",
        "这只僵尸头上戴着铁桶作为防护，因此格外耐打。他的血量大约是普通僵尸的五倍。",
        "铁桶僵尸总是戴着铁桶。一方面是为了在这个冷漠的世界里彰显自己的独特，"
        "但主要还是因为他早就忘了头上还扣着这么个玩意儿。",
    ),
    "newspaper_zombie": (
        "This zombie carries a newspaper that acts as a shield, absorbing 150 damage. When it is "
        "destroyed he will get angry and receive a boost to his speed, both when walking and "
        "eating plants.",
        "Newspaper Zombie was *this* close to finishing his Sudoku puzzle. No wonder he's freaking "
        "out.",
        "这只僵尸手里的报纸可以当盾牌，能吸收 150 点伤害。报纸被撕碎后他会发怒，"
        "行走和啃食植物的速度都会提升。",
        "读报僵尸就差那么一点点就能完成他的数独谜题啦。难怪他会发飙。",
    ),
    "door_zombie": (
        "This zombie carries a screen door in front of him that acts as a shield.",
        "He got his screen door from the last inexpertly defended home he visited, after he ATE "
        "THE HOMEOWNER'S BRAINS.",
        "这只僵尸身前举着一扇铁栅门当盾牌。",
        "他的铁栅门取自上一家他造访过的屋子——就在他吃掉可怜主人的脑袋之后。",
    ),
    "football_zombie": (
        "This zombie wears the outfit of an American football player. He is quite the dangerous "
        "zombie as he is fast and his helmet makes him extremely resistant to damage.",
        "Football Zombie gives 110 percent whenever he's on the field. He's a team player who "
        "delivers both offensively and defensively. He has no idea what a football is.",
        "这只僵尸穿着橄榄球运动员的装备。他既跑得快，头盔又让他极为耐打，相当危险。",
        "橄榄球僵尸在场上的付出是百分之一百一十。他既能进攻也能防守，是个团队型选手。"
        "他完全不知道橄榄球是什么东西。",
    ),
    "dancing_zombie": (
        "This zombie enters the lawn by moonwalking onto it and will summon four Backup Dancers "
        "above, below, in front of and behind him before dancing towards the player's house.",
        "Dancing Zombie's latest album, \"GrarrBRAINSarblarbl,\" is already rocketing up the "
        "undead charts.",
        "这只僵尸会以太空步滑进草坪，并召唤四只伴舞僵尸分别出现在他的上、下、前、后，"
        "然后一路舞向玩家的房子。",
        "跳舞僵尸的最新专辑《抓住脑袋咬啊咬》业已上市，在不死界的排行榜上一路飙升。",
    ),
    "backup_dancer": (
        "These zombies appear in groups of four whenever Dancing Zombie rocks out. If they die he "
        "will summon replacements.",
        "Backup Dancer Zombie spent six years perfecting his art at the Chewliard Performing Arts "
        "School in Zombie New York City.",
        "每当跳舞僵尸开始摇摆，这些僵尸就会四人一组登场。如果他们死了，"
        "跳舞僵尸会再召唤一批替补。",
        "伴舞僵尸曾在僵尸纽约的“咀莉亚表演艺术学院”钻研过六年的舞技。",
    ),
    "ducky_tube_zombie": (
        "This zombie wears an inflatable ducky tube that allows him to float on water in the pool. "
        "He also appears from underwater during the final waves of pool and fog levels.",
        "It takes a certain kind of zombie to be a Ducky Tuber. Not every zombie can handle it. "
        "Some crack. They can't take it. They walk away and give up on brains forever.",
        "这只僵尸套着充气鸭子救生圈，因此能浮在泳池的水面上。"
        "在泳池与浓雾关卡的最后一波，他也会从水下冒出来。",
        "当鸭子僵尸是要有点天赋的，不是每只僵尸都应付得来。有些“人”崩溃了，"
        "他们实在受不了，就此走开，永远放弃了吃脑袋。",
    ),
    "snorkel_zombie": (
        "Snorkel Zombie only appears in the pool, and submerges himself in water while moving, "
        "allowing him to evade most attacks except for lobbed-shot plants. However if there is a "
        "plant in his way he will have to surface to eat it, rendering him vulnerable.",
        "Zombies don't breathe. They don't need air. So why does Snorkel Zombie need a snorkel to "
        "swim underwater? Answer: peer pressure.",
        "潜水僵尸只出现在泳池中。他移动时会潜入水下，能躲开除投掷类植物之外的大多数攻击。"
        "不过如果前方有植物挡路，他就必须浮出水面来啃食，这时他会变得很脆弱。",
        "僵尸们不用呼吸，他们又不需要空气。那么，为什么潜水僵尸还要咬着通气管潜水？"
        "答案：同行压力。",
    ),
    "dolphin_rider_zombie": (
        "This zombie is essentially an aquatic version of Pole Vaulting Zombie, using a dolphin to "
        "vault over the first plant he encounters. The main difference is that he is much faster "
        "before vaulting.",
        "The dolphin is also a zombie.",
        "这只僵尸本质上是撑杆僵尸的水上版：他骑着海豚越过遇到的第一株植物。"
        "主要的区别在于，起跳之前他快得多。",
        "那只海豚也是僵尸。",
    ),
    "balloon_zombie": (
        "This zombie uses a balloon tied around his waist to fly over most plants.",
        "Balloon Zombie really lucked out. The balloon thing really works and none of the other "
        "zombies have picked up on it.",
        "这只僵尸腰间系着一个气球，因此能从大多数植物上方飞过。",
        "气球僵尸真的很走运。气球这招居然真的管用，而且其他僵尸都还没发现。",
    ),
    "miner_zombie": (
        "This zombie uses a pickaxe to dig under your lawn. He will emerge on the left side, wait "
        "five seconds, then begin eating your plants, walking left to right.",
        "Digger Zombie spends three days a week getting his excavation permits in order.",
        "这只僵尸用镐子从草坪底下挖过去。他会在左侧钻出地面，等上五秒，"
        "然后自左向右啃食您的植物。",
        "挖掘僵尸每周都要花三天时间来办理他的挖掘许可证。",
    ),
    "gargantuar": (
        "Gargantuar is a gigantic zombie that boasts the highest health of all standard zombies. "
        "He carries a weapon that he uses to crush plants instead of eating them, and he throws "
        "the Imp on his back once he has lost half of his health.",
        "When Gargantuar walks, the Earth trembles. When he moans, other zombies fall silent. He "
        "is the zombie other zombies dream they could be. But he still can't find a girlfriend.",
        "巨人僵尸体型巨大，是所有普通僵尸中血量最高的。他不啃植物，而是用手中的武器把植物砸碎；"
        "血量掉到一半时，他会把背上的小鬼僵尸扔出去。",
        "当巨人僵尸出行时，大地为之颤抖；当巨人僵尸哀号时，众尸为之沉默。"
        "他简直就是所有僵尸的梦想楷模——可惜，他还是没能找到女朋友。",
    ),
    "imp": (
        "Imp is a small zombie who is hurled by Gargantuar deep into your defenses. After landing "
        "he will proceed on foot. He is faster than standard zombies.",
        "Imp may be small, but he's wiry. He's proficient in zombie judo, zombie karate, and "
        "zombie bare-knuckle brawling. He also plays the melodica.",
        "小鬼僵尸是被巨人僵尸扔进您防线深处的小僵尸。落地之后，他会步行前进。"
        "他比普通僵尸更快。",
        "小鬼僵尸看起来很小，但他很灵巧。他擅长僵尸柔道、僵尸空手道，以及其他僵尸功夫。"
        "他还会吹奏口风琴。",
    ),
    "zombie_boss": (
        "Dr. Zomboss is the intelligent leader of the zombies and the final boss of the game. He "
        "battles the player inside his fearsome Zombot.",
        "Edgar George Zomboss achieved his Doctorate in Thanatology in only two years. Quickly "
        "mastering thanatological technology, he built his fearsome Zombot and set about "
        "establishing absolute dominance of his local subdivision.",
        "僵尸博士是僵尸中具有智慧的首领，也是游戏的最终首领。"
        "他驾驶着可怕的僵尸机甲与玩家决战。",
        "埃德·乔治·僵博仅用两年就取得了死亡学的博士学位。迅速掌握死亡技术后，"
        "他建造了自己的无畏号僵尸机甲，然后开始着手建立对自己这片小区的绝对统治。",
    ),
}

# --------------------------------------------------------------------------------------------
# Resources. Sun, both coins and the diamond keep the original's own wording (the diamond's is
# the Yeti drop's); the three this project added have no original to quote and are described
# from what they do here.
# --------------------------------------------------------------------------------------------
RESOURCES = {
    "sun": (
        "Sun is the resource every plant is paid for with. Click a falling sun to collect it.",
        "Sunlight is the stuff of life. It is also the stuff of planting, which is the same thing "
        "with more zombies in it.",
        "阳光是用来支付所有植物费用的资源。点击落下的阳光即可收集。",
        "阳光是生命之源。它也是种植之源——考虑到僵尸的存在，这两件事其实是一回事。",
    ),
    "coin_silver": (
        "A silver coin, dropped by a defeated zombie or dug up by a Grave Buster.",
        "Silver coins are the small change of the undead economy. Nobody knows what a zombie "
        "would spend them on.",
        "银币，由被击败的僵尸掉落，或被墓碑破坏者从墓碑里挖出。",
        "银币是不死经济里的零钱。没人知道僵尸会拿它们去买什么。",
    ),
    "coin_gold": (
        "A gold coin, worth five times as much as a silver one.",
        "Gold coins are rarer, shinier and worth exactly five silver coins. That is the whole "
        "story.",
        "金币，价值是银币的五倍。",
        "金币更稀有、更闪亮，价值正好是五枚银币。故事就这么简单。",
    ),
    "diamond": (
        "A diamond, dropped by a Zombie Yeti that was killed before it could run away.",
        "Little is known about the Zombie Yeti other than his name, birth date, social security "
        "number, educational history, past work experience, and sandwich preference (roast beef "
        "and Swiss).",
        "钻石，由没来得及逃走的雪人僵尸掉落。",
        "有关雪人僵尸所知不多——除了他的姓名、生辰八字、社会保险号码、受教育程度、"
        "以往工作经历，以及三明治喜好（烤牛肉配瑞士奶酪）。",
    ),
    "money_bag": (
        "A money bag, dropped by a Gargantuar. Worth a great deal of coins at once.",
        "The bag is not actually full of money. It is full of brains. The coins are a courtesy.",
        "钱袋，由巨人僵尸掉落。一次就能换到大量金币。",
        "袋子里装的其实不是钱，而是脑子。金币只是附赠的礼貌。",
    ),
    "energy_bean": (
        "An energy bean, the resource a plant spends to unleash its special ability.",
        "Nobody has ever seen an energy bean grow. They are found, not farmed, which is exactly "
        "what a zombie would say about brains.",
        "能量豆，植物释放特殊能力时消耗的资源。",
        "没人见过能量豆是怎么长出来的。它们是捡来的，不是种出来的——"
        "这句话僵尸拿去说脑子也完全合适。",
    ),
    "redstone": (
        "Redstone, the resource the machinery of the yard runs on.",
        "Redstone is not of this garden. It was here before the lawn, before the house, and "
        "possibly before the zombies, which is saying something.",
        "红石，庭院里的机械赖以运转的资源。",
        "红石并不属于这座庭院。它比草坪更早、比房子更早，甚至可能比僵尸更早——"
        "这话可说明了不少问题。",
    ),
}

GROUPS = {
    "plant": PLANTS,
    "zombie": ZOMBIES,
    "resource": RESOURCES,
}


def entries():
    """Every (key, en, zh) triple this script writes, in a stable order."""
    rows = []
    for category, table in GROUPS.items():
        for name, values in table.items():
            desc_en, flavor_en, desc_zh, flavor_zh = values
            base = f"{category}.pvzce.{name}"
            rows.append((f"{base}.desc", desc_en, desc_zh))
            rows.append((f"{base}.flavor", flavor_en, flavor_zh))
    return rows


def write(locale, index):
    path = os.path.join(LANG, f"{locale}.json")
    with open(path, encoding="utf-8") as handle:
        strings = json.load(handle, object_pairs_hook=dict)
    written = 0
    for key, en, zh in entries():
        value = en if index == 1 else zh
        strings[key] = value
        written += 1
    ordered = {key: strings[key] for key in sorted(strings)}
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(ordered, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    return written


def main():
    total = len(entries())
    print(f"entries: {total}")
    for locale, index in (("zh_cn", 2), ("en_us", 1)):
        print(f"{locale}: wrote {write(locale, index)} keys")
    return 0


if __name__ == "__main__":
    sys.exit(main())
