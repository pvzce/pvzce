#!/usr/bin/env python3
"""Rebuild the existing original minigames, retaining ids and project rewards.

Facts: Patoke/re-plants-vs-zombies Lawn/Challenge.cpp (InitZombieWaves, conveyor pools,
PortalStart, UpdateRainingSeeds), Lawn/Board.cpp (backgrounds, counts, wave budgets).
Tables and algorithms are independently expressed here; no upstream source is copied.
The fixed roll follows the adventure generator's data-first convention. Ladder zombies
are absent from PVZCE and deliberately left out of Last Stand's pool.
"""
import json
import random
from pathlib import Path
from original_levels import ZOMBIES, SPAWN_INTERVAL, HUGE_WAVE_WARNING_TICKS, LEVEL_SEED

ROOT = Path(__file__).resolve().parents[1]
LEVELS = ROOT / 'pvzce-game/src/main/resources/data/pvzce/levels/yard/minigame'
TYPES = {z.repo_id: z for z in ZOMBIES if z.repo_id}
# Plant-headed types belong to this game's first and second Zombotany modes.
PLANT_HEADS = {'zombotany_pea_zombie': (1, 4000, 1), 'zombotany_wallnut_zombie': (4, 3000, 1)}
POOLS = {
    'zombotany': ['zombotany_pea_zombie', 'zombotany_wallnut_zombie'],
    'wallnut_bowling_2': ['basic_zombie', 'conehead_zombie', 'buckethead_zombie',
                         'pole_vaulter_zombie', 'newspaper_zombie', 'dancing_zombie', 'door_zombie'],
    'portal_combat': ['basic_zombie', 'buckethead_zombie', 'football_zombie', 'balloon_zombie'],
    'raining_seeds': ['basic_zombie', 'conehead_zombie', 'buckethead_zombie', 'door_zombie',
                      'football_zombie', 'newspaper_zombie', 'jack_in_the_box_zombie', 'bungee_zombie'],
    'last_stand': ['basic_zombie', 'conehead_zombie', 'buckethead_zombie', 'door_zombie',
                   'football_zombie', 'newspaper_zombie', 'jack_in_the_box_zombie',
                   'pole_vaulter_zombie', 'dolphin_rider_zombie'],
}
COUNTS = {'zombotany': 20, 'wallnut_bowling_2': 30, 'portal_combat': 20,
          'raining_seeds': 40, 'last_stand': 50}


def waves(name):
    rng = random.Random(LEVEL_SEED + list(POOLS).index(name))
    result = []
    for index in range(COUNTS[name]):
        flag = (index + 1) % 10 == 0
        budget = (index + 10) * 2 // 5 + 1 if name == 'last_stand' else index // 3 + 1
        picked = []
        if flag:
            count = min(8, budget)
            budget = int(budget * 2.5)
            if name != 'zombotany':
                picked.extend(['basic_zombie'] * count + ['flag_zombie'])
                budget -= count + 1
        if name == 'wallnut_bowling_2':
            budget *= 4
        while budget > 0 and len(picked) < 50:
            candidates = []
            for zombie in POOLS[name]:
                if zombie in PLANT_HEADS:
                    value, weight, first = PLANT_HEADS[zombie]
                else:
                    z = TYPES[zombie]
                    value, weight, first = z.value, z.pick_weight, z.first_allowed_wave
                if value <= budget and index + 1 >= first:
                    candidates.append((zombie, value, weight))
            if not candidates:
                break
            choice = rng.choices(candidates, weights=[c[2] for c in candidates])[0]
            picked.append(choice[0]); budget -= choice[1]
        entries = {}
        for offset, zombie in enumerate(picked):
            pool = name in ('last_stand', 'raining_seeds')
            water = zombie == 'dolphin_rider_zombie'
            eligible = [2, 3] if water else [0, 1, 4, 5] if pool else list(range(5))
            if pool and zombie in ('basic_zombie', 'conehead_zombie', 'buckethead_zombie') and index >= 5:
                eligible = list(range(6))
            row = eligible[(offset + index) % len(eligible)]
            if pool and row in (2, 3) and not water:
                zombie = {'basic_zombie': 'ducky_tube_zombie', 'conehead_zombie': 'ducky_tube_conehead_zombie',
                          'buckethead_zombie': 'ducky_tube_buckethead_zombie'}[zombie]
            entry = entries.setdefault(zombie, {'id': 'pvzce:' + zombie, 'count': 0, 'rows': []})
            entry['count'] += 1
            if row not in entry['rows']: entry['rows'].append(row)
        delay = 10 if name == 'last_stand' and index % 10 == 0 else 750 if name == 'last_stand' else \
                200 if name == 'portal_combat' and index == 0 else 600 if name == 'wallnut_bowling_2' and index == 0 else \
                1800 if index == 0 else 4500 if index % 10 == 0 else 2500 + rng.randrange(600)
        wave = {'type': 'final' if index == COUNTS[name] - 1 else 'huge' if flag else 'small',
                'delay': delay, 'spawn_interval': SPAWN_INTERVAL, 'entries': list(entries.values())}
        if flag: wave['warning_ticks'] = HUGE_WAVE_WARNING_TICKS
        result.append(wave)
    return result


def scene(pool=False):
    height = 6 if pool else 5
    d = {'pvzce:grass': [f'{x},{y}' for y in range(height) for x in range(9) if not pool or y not in (2, 3)]}
    if pool: d['pvzce:water'] = [f'{x},{y}' for y in (2, 3) for x in range(9)]
    return d


def card(name, weight=1):
    return {'id': 'pvzce:' + name, 'weight': weight}


def hints(*texts):
    return [{'trigger': 'on_start', 'text': text, 'duration_ticks': 600} for text in texts] + [{'trigger': 'on_card_refused'}]


def rebuild():
    for name in (*POOLS, 'whack_a_zombie'):
        p = LEVELS / (name + '.json'); d = json.loads(p.read_text())
        pool = name in ('last_stand', 'raining_seeds')
        night = name in ('portal_combat', 'raining_seeds', 'whack_a_zombie')
        d.update(height=6 if pool else 5, scene=scene(pool), initial_sun=50,
                 hidden_scene_elements=['pvzce:grass'] + (['pvzce:water'] if pool else []), disable_shaders=True,
                 background='pvzce:textures/gui/screen/level/background' + ('4' if pool and night else '3' if pool else '2' if night else '1'))
        d['rules'] = {'pvzce:day_length': 0, 'pvzce:night_length': 360000 if night else -1,
                      'pvzce:sun_spawn_interval_min': 0, 'pvzce:sun_spawn_interval_max': 0,
                      'pvzce:zombie_sun_drop_chance': 0.0, 'pvzce:graves_spawn_night': False}
        d['mechanics'] = []; d.pop('dialogue', None)
        if name != 'whack_a_zombie': d['waves'] = waves(name)
        d['buffs'] = []
        if name == 'zombotany':
            d['description'] = '植物僵尸一代：白天草坪上只出现豌豆头和坚果头。豌豆头会朝植物开火，坚果头需要持续攻击；选择植物建立阳光经济，守住两面旗帜。'
            d['slots'] = ['pvzce:sun', 'pvzce:shovel']; d['max_seed_slots'] = 10; d['seed_screen'] = True
            d['rules'].update({'pvzce:sun_spawn_interval_min': 480, 'pvzce:sun_spawn_interval_max': 720, 'pvzce:sun_spawn_initial_ticks': 300})
            d['hints'] = hints('豌豆头会向左射击；坚果挡子弹，小喷菇和地刺也能避开豌豆的高度。')
        elif name == 'wallnut_bowling_2':
            d['initial_sun'] = 0; d['slots'] = []; d['seed_screen'] = False
            d['description'] = '坚果保龄球二代：传送带送来普通坚果、爆炸坚果和巨大坚果。普通坚果撞击换行，爆炸坚果清掉一片，巨大坚果直线碾压；三面旗帜，没有小推车。'
            d['mechanics'] = [{'type': 'pvzce:conveyor', 'interval_ticks': 150, 'capacity': 10, 'initial_cards': 2,
                                'cards': [card('bowling_nut', 85), card('explosive_nut', 15), card('giant_nut', 15)]},
                               {'type': 'pvzce:placement_zone', 'max_x': 2}, {'type': 'pvzce:mower', 'rows': []}]
            d['hints'] = hints('只在左边三列投球；普通坚果会撞击换行，巨大坚果沿直线碾过去。', '爆炸坚果撞到僵尸就爆炸，留给密集的尸群。')
        elif name == 'portal_combat':
            d['initial_sun'] = 0; d['slots'] = ['pvzce:shovel']; d['seed_screen'] = False
            d['description'] = '夜晚的传送带关：两对传送门会传送僵尸、直线子弹和小推车。每对门使用同一种形状，门会定时换位；用豌豆、火炬树桩和仙人掌守住两面旗帜。'
            # Original screen rows count downward; world rows count upward.
            d['mechanics'] = [{'type': 'pvzce:conveyor', 'interval_ticks': 360, 'capacity': 10, 'initial_cards': 2,
                              'cards': [card(n, w) for n, w in [('pea_shooter', 25), ('repeater', 20), ('torchwood', 10), ('cactus', 15), ('wall_nut', 15), ('cherry_bomb', 15)]]},
                             {'type': 'pvzce:portal', 'pairs': [{'ax': 2, 'ay': 4, 'bx': 9, 'by': 3}, {'ax': 9, 'ay': 1, 'bx': 2, 'by': 0}],
                              'initial_relocate_ticks': 5400, 'relocate_interval_ticks': 3600}]
            d['hints'] = hints('方形门彼此相通，圆形门彼此相通；直线子弹和小推车也会穿门。', '传送门会定时换位；仙人掌负责空中的气球僵尸。')
        elif name == 'raining_seeds':
            d['initial_sun'] = 0; d['slots'] = ['pvzce:shovel']; d['max_seed_slots'] = 1; d['seed_screen'] = False
            seeds = ['pea_shooter', 'cherry_bomb', 'wall_nut', 'potato_mine', 'snow_pea', 'chomper', 'repeater',
                     'puff_shroom', 'fume_shroom', 'hypno_shroom', 'scaredy_shroom', 'ice_shroom', 'doom_shroom',
                     'lily_pad', 'squash', 'threepeater', 'tangle_kelp', 'jalapeno', 'spikeweed', 'torchwood', 'tall_nut',
                     'sea_shroom', 'plantern', 'cactus', 'blover', 'split_pea', 'starfruit', 'pumpkin', 'magnet_shroom',
                     'cabbage_pult', 'kernel_pult', 'melon_pult']
            d['description'] = '夜晚泳池里下起种子雨：种子包从天上落下，捡起来免费种植，没有阳光和选卡。莲叶能让陆地植物种进泳池；种子包落地后二十秒消失，拿在手上暂停计时。守住四面旗帜。'
            d['mechanics'] = [{'type': 'pvzce:seed_rain', 'interval_ticks': 300, 'max_interval_ticks': 599,
                              'initial_delay_ticks': 60, 'lily_pad_bias': True, 'falling': True,
                              'cards': [card(n) for n in seeds]}]
            d['hints'] = hints('捡起落下的种子包，再点草坪免费种下；水路先铺莲叶。', '种子包落地后二十秒消失，最后五秒闪烁；拿在手上不计时。')
        elif name == 'last_stand':
            d['initial_sun'] = 5000; d['slots'] = ['pvzce:sun', 'pvzce:shovel']; d['max_seed_slots'] = 10; d['seed_screen'] = True
            d['description'] = '先用5000阳光在泳池布阵，再按开始。禁止阳光生产植物，天上和僵尸都不给阳光；每守住一面旗帜补给250阳光，可保留阵容修整，再按开始继续。守住五面旗帜获胜，没有小推车。'
            d['mechanics'] = [{'type': 'pvzce:preparation', 'manual': True, 'refund': True,
                              'waves_per_stage': 10, 'stage_sun': 250,
                              'excluded_cards': ['pvzce:' + n for n in ['sunflower', 'twin_sunflower', 'sun_shroom', 'marigold']]},
                             {'type': 'pvzce:mower', 'rows': []}, {'type': 'pvzce:rake', 'rows': []}]
            d['hints'] = hints('水上先铺莲叶；准备时植物无冷却，铲除全额退款。', '每面旗帜清场后补给250阳光。修整完成，按开始继续。')
        else:
            original = json.loads((LEVELS.parent / 'adventure/2_5.json').read_text())
            d['scene'] = original['scene']; d['name'] = '打地鼠'; d['slots'] = original['slots']; d['initial_sun'] = 0; d['seed_screen'] = False
            d['description'] = '夜晚的打地鼠：空手点击锤击僵尸，土豆雷、墓碑吞噬者和樱桃炸弹辅助防守。前段只有普通僵尸，随后出现路障和铁桶，末波墓碑一起冒怪；没有小推车。'
            d['mechanics'] = [{'type': 'pvzce:rake', 'rows': []}, {'type': 'pvzce:grave_spawner', 'phased': True,
                              'zombies': ['pvzce:basic_zombie', 'pvzce:conehead_zombie', 'pvzce:buckethead_zombie'],
                              'min_graves': 5, 'initial_graves': 9, 'interval': 100, 'min_x': 3, 'max_x': 8},
                             {'type': 'pvzce:tool', 'tool': 'pvzce:hammer', 'default': True, 'cooldown': 0,
                              'cost': {'resources': {}}, 'damage': 900, 'single_target': True},
                             {'type': 'pvzce:mower', 'rows': []}, {'type': 'pvzce:wave_pacing', 'health_drain': False, 'clear_reward_factor': 1}]
            d['waves'] = [{'type': 'final' if i == 11 else 'small', 'delay': 1800 if i == 0 else 2000,
                           'spawn_interval': 15, 'entries': []} for i in range(12)]
            d['rules'].update({'pvzce:zombie_sun_drop_chance': 0.1, 'pvzce:zombie_rise_ticks': 50})
            d['hints'] = hints('空手点僵尸即可锤击；路障要两锤，铁桶要三锤。', '墓碑吞噬者吞噬期间，这座墓碑不会冒出僵尸。')
        if name in ('wallnut_bowling_2', 'portal_combat', 'raining_seeds', 'whack_a_zombie'):
            d['buffs'] = ['pvzce:auto_collect']
        d['unlock_resources'] = {'pvzce:sun': True} if 'pvzce:sun' in d['slots'] else {}
        d['music'] = {'cues': [{'at_tick': 0, 'track': 'background', 'event': 'pvzce:music/loon_boon',
                               'loop': True, 'volume': .85, 'fade_seconds': 1.0}]}
        p.write_text(json.dumps(d, ensure_ascii=False, indent=2) + '\n')
        print(name, len(d['waves']), 'waves', sum(e['count'] for w in d['waves'] for e in w['entries']), 'written zombies')


if __name__ == '__main__': rebuild()
