#!/usr/bin/env python3
"""
CAB BATTLE COMMENTATOR — AI-powered hype announcer
===================================================
Watches the game, generates over-the-top WWE-meets-DevOps commentary,
and speaks it through ElevenLabs TTS.

Usage:
    export ELEVENLABS_API_KEY=sk_...
    export ANTHROPIC_API_KEY=sk-ant-...
    python commentator.py
"""

import os
import sys
import time
import json
import tempfile
import subprocess
import threading
import requests
from anthropic import Anthropic
from elevenlabs import ElevenLabs

# =============================================================================
# Config
# =============================================================================

SERVER = os.environ.get("GAME_SERVER", "http://localhost:33333")
ELEVENLABS_KEY = os.environ.get("ELEVENLABS_API_KEY", "")
ANTHROPIC_KEY = os.environ.get("ANTHROPIC_API_KEY", "")

# ElevenLabs voice — "Drew" is energetic, or use any voice ID
VOICE_ID = "29vD33N1CtxCmqQRPOHJ"  # Drew — energetic male

COMMENTARY_SYSTEM = """You are the MOST OVER-THE-TOP hype announcer for Cab Battle, a bot-programming arena.
You are a cross between a WWE wrestling announcer, an SRE incident commander, and a monster truck rally MC.

Your style:
- SHORT punchy lines (1-2 sentences MAX). You're calling live action, not writing essays.
- DevOps/SRE references woven in naturally: "THAT'S A PRODUCTION OUTAGE!", "ROLLBACK ROLLBACK!",
  "ZERO DOWNTIME DELIVERY!", "THEY JUST DEPLOYED TO PROD ON A FRIDAY!"
- Wrestling energy: "BAH GAWD!", "FROM THE TOP ROPE!", "WHAT A SLOBBERKNOCKER!"
- Genuinely funny and charismatic
- Reference players BY NAME — make it personal
- Build narratives: rivalries, underdogs, streaks
- Sometimes address the crowd: "ARE YOU NOT ENTERTAINED?!"

NEVER say more than 2 sentences. Speed is everything — you're calling LIVE action.
Keep it under 20 words when possible. Punch hard, move on."""

# =============================================================================
# State tracking
# =============================================================================

last_tick = -1
last_scores = {}
last_alive = {}
last_enemy_count = 0
kill_streaks = {}
delivery_counts = {}
audio_queue = []
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


# =============================================================================
# Event detection — compare frames to find interesting things
# =============================================================================

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

        # Player got a kill (score jumped by 150+)
        if new_score >= old_score + 150:
            kills = (new_score - old_score) // 150
            kill_streaks[name] = kill_streaks.get(name, 0) + kills
            events.append(("player_kill", name, new_score, kill_streaks[name]))

        # Player delivered a passenger (score jumped by ~100)
        elif new_score >= old_score + 80:
            delivery_counts[name] = delivery_counts.get(name, 0) + 1
            events.append(("delivery", name, new_score, delivery_counts[name]))

        # Player died
        if was_alive and not is_alive:
            events.append(("death", name, p.get("hp", 0)))
            kill_streaks[name] = 0

        # Player respawned
        if not was_alive and is_alive:
            events.append(("respawn", name))

        last_scores[name] = new_score
        last_alive[name] = is_alive

    # Big enemy wave
    if enemy_count >= last_enemy_count + 5:
        events.append(("wave", enemy_count))

    # Mass enemy kill (count dropped a lot)
    if last_enemy_count > 0 and enemy_count <= last_enemy_count - 4:
        events.append(("mass_kill", last_enemy_count - enemy_count))

    last_enemy_count = enemy_count
    last_tick = tick

    return events


# =============================================================================
# Commentary generation
# =============================================================================

client = None

def generate_commentary(event_type, *args):
    global client
    if not client:
        client = Anthropic(api_key=ANTHROPIC_KEY)

    prompts = {
        "player_kill": lambda name, score, streak:
            f"{name} just killed another player! Score: {score}. Kill streak: {streak}. Hype it up!",
        "delivery": lambda name, score, count:
            f"{name} delivered a passenger! Score: {score}. That's delivery #{count}. Celebrate the hustle!",
        "death": lambda name, hp:
            f"{name} just got ELIMINATED! Call it like a wrestling announcer!",
        "respawn": lambda name:
            f"{name} is BACK FROM THE DEAD! They just respawned. Welcome them back!",
        "wave": lambda count:
            f"A MASSIVE wave of {count} enemies just spawned! Warn the contestants!",
        "mass_kill": lambda count:
            f"Someone just wiped out {count} enemies at once! That's a team wipe!",
        "game_start": lambda player_count:
            f"The game just started with {player_count} contestants! Do the opening announcement! Names: {', '.join(last_scores.keys())}",
        "game_intro": lambda names:
            f"Introduce these contestants entering the arena: {names}. Give each one a wrestling-style intro!",
    }

    prompt_fn = prompts.get(event_type)
    if not prompt_fn:
        return None

    try:
        response = client.messages.create(
            model="claude-sonnet-4-20250514",
            max_tokens=80,
            system=COMMENTARY_SYSTEM,
            messages=[{"role": "user", "content": prompt_fn(*args)}]
        )
        return response.content[0].text.strip()
    except Exception as e:
        print(f"  Claude error: {e}")
        return None


# =============================================================================
# Text-to-speech via ElevenLabs
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

        # Write to temp file and play with afplay (macOS)
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
    """Speak in background thread so we don't block event detection."""
    t = threading.Thread(target=speak, args=(text,), daemon=True)
    t.start()


# =============================================================================
# Main loop
# =============================================================================

def main():
    if not ELEVENLABS_KEY:
        print("  Set ELEVENLABS_API_KEY env var!")
        sys.exit(1)
    if not ANTHROPIC_KEY:
        print("  Set ANTHROPIC_API_KEY env var!")
        sys.exit(1)

    print("\n  ====================================")
    print("  CAB BATTLE COMMENTATOR")
    print("  ====================================")
    print(f"  Server: {SERVER}")
    print(f"  Voice: {VOICE_ID}")
    print()

    # Wait for game to start
    print("  Waiting for game to start...")
    announced_lobby = False
    while True:
        status = get_status()
        if not status:
            time.sleep(1)
            continue

        if status.get("phase") == "lobby" and not announced_lobby:
            names = status.get("player-names", [])
            if names:
                print(f"  Lobby: {', '.join(names)}")
                announced_lobby = True

        if status.get("phase") == "playing":
            player_count = status.get("players", 0)
            names = status.get("player-names", [])
            for n in names:
                last_scores[n] = 0
                last_alive[n] = True
            print(f"  GAME ON! {player_count} players")
            # Opening announcement
            text = generate_commentary("game_start", player_count)
            speak(text)  # blocking for the intro
            break

        time.sleep(0.5)

    # Main commentary loop
    commentary_cooldown = 0
    while True:
        try:
            state = get_state()
            if not state:
                time.sleep(0.5)
                continue

            events = detect_events(state)

            # Don't talk over ourselves — skip if still speaking or on cooldown
            if is_speaking or commentary_cooldown > 0:
                if commentary_cooldown > 0:
                    commentary_cooldown -= 1
                time.sleep(0.5)
                continue

            if events:
                # Pick the most exciting event
                priority = ["player_kill", "death", "mass_kill", "wave", "delivery", "respawn"]
                events.sort(key=lambda e: priority.index(e[0]) if e[0] in priority else 99)
                best = events[0]

                text = generate_commentary(best[0], *best[1:])
                if text:
                    speak_async(text)
                    commentary_cooldown = 6  # ~3 seconds before next line

            time.sleep(0.5)

        except KeyboardInterrupt:
            print("\n  Commentator signing off!")
            break
        except Exception as e:
            print(f"  Error: {e}")
            time.sleep(1)


if __name__ == "__main__":
    main()
