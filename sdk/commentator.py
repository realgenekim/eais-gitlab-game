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
VOICE_ID = "N2lVS1w4EtoT3dr4eOWO"  # Callum — Husky Trickster, Rick Sanchez energy

# =============================================================================
# Rick and Morty style + IT Operations commentary
# =============================================================================

LINES_KILL = [
    "Wubba lubba dub dub! {killer} just absolutely WRECKED {victim}! That's what you get for running unpatched in production!",
    "Oh geez {victim}! {killer} just deployed a hotfix right to your FACE! No change advisory board needed!",
    "{killer} sends {victim} to the shadow realm! That's worse than a P1 incident at 3 AM, Morty!",
    "BOOM! {victim} is DOWN! {killer} just did a force push to main and it actually WORKED!",
    "{killer} just eliminated {victim}! Somebody call the incident commander! Oh wait, there IS no incident commander!",
    "Get schwifty! {killer} just turned {victim} into a post-mortem! Blameless, of course!",
    "Holy cow {victim}! {killer} just rolled back your entire existence! That's what happens when you skip the CAB meeting!",
    "{killer} just sent {victim} through the ENTIRE deployment pipeline! Straight to DECOMMISSIONED!",
    "That kill was more brutal than a failed database migration, Morty! {killer} is running the Three Ways out here!",
    "{killer} just turned {victim} into technical debt! Gene Kim would write a BOOK about this carnage!",
    "INCIDENT REPORT: {victim} got absolutely DevOps'd by {killer}! The value stream just got VIOLENT!",
    "{killer} treating {victim} like a legacy system! DECOMMISSIONED! Time to modernize, baby!",
    "Oh that's NASTY! {killer} just chaos monkey'd {victim} right out of the cluster!",
    "{killer} with the ELIMINATION! That's what the Unicorn Project calls a REBELLION, Morty!",
]

LINES_DEATH = [
    "{name} just got ELIMINATED! I've seen better uptime from a Raspberry Pi in a hurricane, Morty!",
    "Oh geez, {name} is DOWN! Their SLA just went to zero percent! Somebody page the on-call!",
    "{name} just had a total system failure! That's what you get for not having redundancy!",
    "REST IN PIECES {name}! That was a career limiting move right there! Worse than deleting prod!",
    "{name} is OUT! Their mean time to recovery is looking pretty INFINITE right now!",
    "And {name} goes down like a deployment on a Friday! When will they LEARN, Morty?!",
    "{name} just experienced an unplanned outage! The auditors are gonna have a FIELD DAY with this!",
    "BLAMELESS POST-MORTEM for {name}! Just kidding, I'm totally blaming them! That was TERRIBLE!",
    "{name} had ZERO observability! Couldn't even see that attack coming! Get some monitoring, Morty!",
    "{name} just proved why you need a disaster recovery plan! The Visible Ops handbook is WEEPING right now!",
    "That's what happens when your toil exceeds fifty percent, {name}! The SRE handbook tried to WARN you!",
    "{name} went down like a monolith in a microservices world! Time to decompose, literally!",
]

LINES_DELIVERY = [
    "{name} just delivered a passenger! That's what we call a zero downtime deployment, baby!",
    "Look at {name} delivering value to the customer! The auditors would be SO proud right now!",
    "{name} with the delivery! That's continuous delivery in action, Morty! The Phoenix Project would be proud!",
    "Oh snap! {name} just completed a value stream delivery! Somebody update the DORA metrics!",
    "{name} drops off a passenger! Lead time to delivery? CHEF'S KISS! That's elite performance!",
    "{name} flowing work through the system like a BOSS! That's the First Way, Morty! Flow, flow, FLOW!",
    "BEAUTIFUL delivery by {name}! Short lead time, small batch size, Gene Kim is smiling somewhere!",
    "{name} with the value stream optimization! Deployment frequency just went through the ROOF!",
    "That delivery was smoother than a fully automated CI CD pipeline! {name} is an IT Revolution LEGEND!",
    "{name} just proved that speed and safety are NOT opposites! The DevOps Handbook told you SO!",
]

LINES_WAVE = [
    "Oh geez Morty, a MASSIVE wave of enemies just deployed to production! No rollback plan! We're SCREWED!",
    "INCOMING! It's like someone merged to main without running the tests! Enemies EVERYWHERE!",
    "Holy smokes, look at all those floopies! This is worse than a cascading failure in microservices!",
    "More enemies flooding in! This is a full blown SEV 1 incident! All hands on deck!",
    "It's a SWARM! This is what happens when you ignore your security vulnerabilities, Morty! PATCH YOUR SYSTEMS!",
    "NEW WAVE of enemies! This is like a DDoS attack but with TEETH! The war room is OPEN!",
    "Enemies everywhere! Somebody didn't rotate their credentials and now we're getting OWNED!",
    "WAVE INCOMING! This is worse than the time Bill from The Phoenix Project had to deal with that payroll failure!",
]

LINES_MASS_KILL = [
    "Wubba lubba dub dub! That was a MASSACRE! Someone just automated their entire kill chain!",
    "TOTAL DOMINATION! That's what we call infrastructure as CARNAGE! Beautiful!",
    "They just wiped out a whole SQUAD! That's more efficient than a kubernetes auto-scaler on a good day!",
    "MASS ELIMINATION! That's what happens when you invest in automation! The Third Way, Morty! CONTINUOUS LEARNING!",
    "ANNIHILATED! Someone just ran terraform destroy on those enemies! No approval, no regrets!",
]

LINES_GAME_START = [
    "Welcome to the THUNDERDOME, you beautiful nerds! IT Revolution presents the greatest bot battle in the multiverse! Let's GOOO!",
    "Alright Morty, the bots are deployed, the arena is HOT, and somebody's about to have a very bad day! Wubba lubba dub dub!",
    "The change freeze is OVER! The bots are loose in production! No approval needed! Gene Kim said SHIP IT!",
    "Ladies and gentlemen, welcome to the Enterprise AI Summit BATTLE ARENA! Where DevOps meets DESTRUCTION! Brought to you by IT Revolution!",
]

LINES_RESPAWN = [
    "{name} is BACK! That's some elite disaster recovery right there! Mean time to restore? IMPRESSIVE!",
    "{name} respawns! Auto-healing infrastructure at its finest, Morty! Round TWO!",
    "Oh snap {name} just came back! That's better failover than most Fortune 500 companies!",
    "{name} just recovered faster than a blue green deployment! The Second Way, Morty! FAST FEEDBACK!",
    "{name} is back online! That's what we call a self-healing system! The SRE book would be PROUD!",
    "RESPAWN! {name} just did a rolling update on their LIFE! Back in the game!",
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
