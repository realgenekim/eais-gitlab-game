#!/usr/bin/env python3
"""
CAB BATTLE COMMENTATOR — Mike Tyson voice via ElevenLabs
========================================================
Pre-written hype lines, no LLM needed. Just ElevenLabs TTS.

Usage:
    export ELEVENLABS_API_KEY=sk_...
    python commentator.py
"""

import os
import sys
import time
import random
import tempfile
import subprocess
import threading
import requests
from elevenlabs import ElevenLabs

SERVER = os.environ.get("GAME_SERVER", "http://localhost:33333")
ELEVENLABS_KEY = os.environ.get("ELEVENLABS_API_KEY", "")
VOICE_ID = "SOYHLrjzK2X1ezoPC6cr"  # Harry — Fierce Warrior, rough male

# =============================================================================
# Pre-written Mike Tyson commentary lines
# =============================================================================

LINES_KILL = [
    "{killer} just DESTROYED {victim}! That's what happens when you step in the ring with a CHAMPION!",
    "OH! {victim} just got knocked OUT! {killer} is a BEAST in this arena!",
    "{killer} sends {victim} to the SHADOW REALM! Everybody has a plan until they get punched in the mouth!",
    "BOOM! {victim} is DOWN! {killer} showing NO MERCY out there!",
    "{killer} just eliminated {victim}! That was VICIOUS! I love it!",
    "Goodnight {victim}! {killer} just put them to SLEEP!",
    "{victim} just got WRECKED! {killer} is on a RAMPAGE!",
]

LINES_DEATH = [
    "{name} just got ELIMINATED! That is PATHETIC!",
    "OH NO! {name} is DOWN! Get up! GET UP! ... they're not getting up!",
    "{name} just got sent to the SHADOW REALM! Brutal!",
    "REST IN PIECES, {name}! The arena shows NO mercy!",
    "{name} is OUT! That was DEVASTATING to watch!",
]

LINES_DELIVERY = [
    "{name} just delivered a passenger! Now THAT is how you make money in this business!",
    "SPECIAL DELIVERY from {name}! Getting PAID out there!",
    "{name} with the delivery! Smart AND tough! Respect!",
    "{name} drops off a passenger! That's a hundred points BABY!",
]

LINES_WAVE = [
    "HERE THEY COME! A massive wave of enemies! This is about to get CRAZY!",
    "INCOMING! The enemies are SWARMING! Nobody is safe!",
    "Look at all those enemies! This arena is about to become a WARZONE!",
    "More enemies flooding in! Who will SURVIVE?!",
]

LINES_MASS_KILL = [
    "MASSACRE! Someone just wiped out a whole squad of enemies! INCREDIBLE!",
    "That was a SLAUGHTER! Enemies dropping like flies!",
    "TOTAL DOMINATION! The enemies didn't stand a CHANCE!",
]

LINES_GAME_START = [
    "LADIES AND GENTLEMEN! Welcome to the THUNDERDOME! Let the BATTLE BEGIN!",
    "The cage is LOCKED! The bots are LOOSE! Let's see who SURVIVES!",
    "IT'S GO TIME! May the best bot WIN! Or at least survive!",
]

LINES_RESPAWN = [
    "{name} is BACK FROM THE DEAD! They want REVENGE!",
    "{name} respawns! Round two! FIGHT!",
]

# =============================================================================
# State tracking
# =============================================================================

last_tick = -1
last_scores = {}
last_alive = {}
last_enemy_count = 0
is_speaking = False


def get_state():
    try:
        resp = requests.get(f"{SERVER}/game/frame", timeout=2)
        data = resp.json()
        if data.get("latest-tick"):
            frame = requests.get(f"{SERVER}/game/frame?tick={data['latest-tick']}", timeout=2)
            return frame.json()
    except Exception:
        pass
    return None


def get_status():
    try:
        return requests.get(f"{SERVER}/game/status", timeout=2).json()
    except Exception:
        return None


def detect_events(state):
    global last_tick, last_scores, last_alive, last_enemy_count
    events = []

    if not state or "tick" not in state:
        return events

    tick = state["tick"]
    if tick <= last_tick:
        return events

    players = {p["name"]: p for p in state.get("players", [])}
    enemies = state.get("enemies", [])
    enemy_count = len(enemies)

    for name, p in players.items():
        old_score = last_scores.get(name, 0)
        was_alive = last_alive.get(name, True)
        new_score = p.get("score", 0)
        is_alive = p.get("alive", True)

        if new_score >= old_score + 150:
            events.append(("player_kill", name, new_score))
        elif new_score >= old_score + 80:
            events.append(("delivery", name))

        if was_alive and not is_alive:
            events.append(("death", name))

        if not was_alive and is_alive:
            events.append(("respawn", name))

        last_scores[name] = new_score
        last_alive[name] = is_alive

    if enemy_count >= last_enemy_count + 5:
        events.append(("wave", enemy_count))

    if last_enemy_count > 0 and enemy_count <= last_enemy_count - 4:
        events.append(("mass_kill", last_enemy_count - enemy_count))

    last_enemy_count = enemy_count
    last_tick = tick
    return events


def pick_line(event_type, *args):
    if event_type == "player_kill":
        name = args[0]
        # We don't know who the killer is from score alone, use generic
        line = random.choice(LINES_KILL)
        return line.format(killer=name, victim="their opponent")
    elif event_type == "death":
        return random.choice(LINES_DEATH).format(name=args[0])
    elif event_type == "delivery":
        return random.choice(LINES_DELIVERY).format(name=args[0])
    elif event_type == "wave":
        return random.choice(LINES_WAVE)
    elif event_type == "mass_kill":
        return random.choice(LINES_MASS_KILL)
    elif event_type == "respawn":
        return random.choice(LINES_RESPAWN).format(name=args[0])
    elif event_type == "game_start":
        return random.choice(LINES_GAME_START)
    return None


# =============================================================================
# Text-to-speech
# =============================================================================

eleven = None

def speak(text):
    global eleven, is_speaking
    if not text:
        return
    if not eleven:
        eleven = ElevenLabs(api_key=ELEVENLABS_KEY)

    print(f"  >> {text}")

    try:
        audio = eleven.text_to_speech.convert(
            text=text,
            voice_id=VOICE_ID,
            model_id="eleven_turbo_v2_5",
            output_format="mp3_44100_128",
        )

        with tempfile.NamedTemporaryFile(suffix=".mp3", delete=False) as f:
            for chunk in audio:
                f.write(chunk)
            f.flush()
            tmppath = f.name

        is_speaking = True
        subprocess.run(["afplay", tmppath], check=False)
        is_speaking = False
        os.unlink(tmppath)

    except Exception as e:
        print(f"  ElevenLabs error: {e}")
        is_speaking = False


def speak_async(text):
    t = threading.Thread(target=speak, args=(text,), daemon=True)
    t.start()


# =============================================================================
# Main loop
# =============================================================================

def main():
    if not ELEVENLABS_KEY:
        print("  Set ELEVENLABS_API_KEY env var!")
        sys.exit(1)

    print("\n  ====================================")
    print("  CAB BATTLE COMMENTATOR")
    print("  Voice: Mike Tyson mode")
    print(f"  Server: {SERVER}")
    print("  ====================================\n")

    # Wait for game to start
    print("  Waiting for game to start...")
    while True:
        status = get_status()
        if not status:
            time.sleep(1)
            continue
        if status.get("phase") == "playing":
            names = status.get("player-names", [])
            for n in names:
                last_scores[n] = 0
                last_alive[n] = True
            print(f"  GAME ON! {len(names)} players: {', '.join(names)}")
            # Opening announcement
            speak(random.choice(LINES_GAME_START))
            break
        time.sleep(0.5)

    # Main commentary loop
    cooldown = 0
    while True:
        try:
            state = get_state()
            if not state:
                time.sleep(0.5)
                continue

            events = detect_events(state)

            if is_speaking or cooldown > 0:
                if cooldown > 0:
                    cooldown -= 1
                time.sleep(0.5)
                continue

            if events:
                priority = ["player_kill", "death", "mass_kill", "wave", "delivery", "respawn"]
                events.sort(key=lambda e: priority.index(e[0]) if e[0] in priority else 99)
                best = events[0]

                text = pick_line(best[0], *best[1:])
                if text:
                    speak_async(text)
                    cooldown = 8  # ~4 seconds before next line

            time.sleep(0.5)

        except KeyboardInterrupt:
            print("\n  Commentator signing off!")
            break
        except Exception as e:
            print(f"  Error: {e}")
            time.sleep(1)


if __name__ == "__main__":
    main()
