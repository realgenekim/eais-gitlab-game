#!/usr/bin/env python3
"""
CAB BATTLE COMMENTATOR — Pre-cached audio, zero-latency playback
================================================================
On first run, generates ALL commentary lines as MP3s via ElevenLabs.
During the game, just picks a file and plays it instantly.

Usage:
    python commentator.py              # reads .env for ELEVENLABS_API_KEY
    python commentator.py --generate   # regenerate all audio clips
"""

import os
import sys
import time
import random
import hashlib
import subprocess
import threading
import queue
import requests

# Load .env if present
ENV_PATH = os.path.join(os.path.dirname(__file__), ".env")
if os.path.exists(ENV_PATH):
    for line in open(ENV_PATH):
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            os.environ.setdefault(k.strip(), v.strip())

SERVER = os.environ.get("GAME_SERVER", "http://localhost:33333")
ELEVENLABS_KEY = os.environ.get("ELEVENLABS_API_KEY", "")
VOICE_ID = "N2lVS1w4EtoT3dr4eOWO"  # Callum — Husky Trickster
CACHE_DIR = os.path.join(os.path.dirname(__file__), "audio_cache")

# =============================================================================
# All commentary lines — keyed by event type
# =============================================================================

ALL_LINES = {
    "kill": [
        "Wubba lubba dub dub! {killer} just absolutely WRECKED {victim}! That's what you get for running unpatched in production!",
        "Oh geez {victim}! {killer} just deployed a hotfix right to your FACE! No change advisory board needed!",
        "{killer} sends {victim} to the shadow realm! That's worse than a P1 incident at 3 AM, Morty!",
        "BOOM! {victim} is DOWN! {killer} just did a force push to main and it actually WORKED!",
        "{killer} just eliminated {victim}! Somebody call the incident commander!",
        "Get schwifty! {killer} just turned {victim} into a post-mortem! Blameless, of course!",
        "{killer} just sent {victim} through the ENTIRE deployment pipeline! Straight to DECOMMISSIONED!",
        "That kill was more brutal than a failed database migration, Morty! {killer} is running the Three Ways out here!",
        "{killer} just turned {victim} into technical debt! Gene Kim would write a BOOK about this carnage!",
        "INCIDENT REPORT: {victim} got absolutely DevOps'd by {killer}! The value stream just got VIOLENT!",
        "{killer} treating {victim} like a legacy system! DECOMMISSIONED!",
        "Oh that's NASTY! {killer} just chaos monkey'd {victim} right out of the cluster!",
        "{killer} with the ELIMINATION! That's what the Unicorn Project calls a REBELLION, Morty!",
    ],
    "death": [
        "{name} just got ELIMINATED! I've seen better uptime from a Raspberry Pi in a hurricane, Morty!",
        "Oh geez, {name} is DOWN! Their SLA just went to zero percent! Somebody page the on-call!",
        "{name} just had a total system failure! That's what you get for not having redundancy!",
        "REST IN PIECES {name}! That was worse than deleting prod!",
        "{name} is OUT! Their mean time to recovery is looking pretty INFINITE right now!",
        "And {name} goes down like a deployment on a Friday! When will they LEARN, Morty?!",
        "BLAMELESS POST-MORTEM for {name}! Just kidding, I'm totally blaming them! That was TERRIBLE!",
        "{name} had ZERO observability! Get some monitoring, Morty!",
        "{name} just proved why you need a disaster recovery plan! The Visible Ops handbook is WEEPING!",
        "That's what happens when your toil exceeds fifty percent, {name}! The SRE handbook tried to WARN you!",
        "{name} went down like a monolith in a microservices world!",
    ],
    "delivery": [
        "{name} just delivered a passenger! That's a zero downtime deployment, baby!",
        "Look at {name} delivering value to the customer! The auditors would be SO proud!",
        "{name} with the delivery! Continuous delivery in action! The Phoenix Project would be proud!",
        "Oh snap! {name} just completed a value stream delivery! Update the DORA metrics!",
        "{name} drops off a passenger! Lead time to delivery? CHEF'S KISS! Elite performance!",
        "{name} flowing work through the system like a BOSS! That's the First Way, Morty! FLOW!",
        "BEAUTIFUL delivery by {name}! Short lead time, small batch size, Gene Kim is smiling!",
        "That delivery was smoother than a fully automated CI CD pipeline! {name} is a LEGEND!",
        "{name} just proved that speed and safety are NOT opposites! The DevOps Handbook told you SO!",
    ],
    "wave": [
        "A MASSIVE wave of enemies just deployed to production! No rollback plan! We're SCREWED!",
        "INCOMING! Someone merged to main without running the tests! Enemies EVERYWHERE!",
        "Look at all those floopies! This is worse than a cascading failure in microservices!",
        "More enemies flooding in! Full blown SEV 1 incident! All hands on deck!",
        "It's a SWARM! This is what happens when you ignore your security vulnerabilities, Morty!",
        "NEW WAVE! This is like a DDoS attack but with TEETH! The war room is OPEN!",
        "WAVE INCOMING! Worse than when Bill from The Phoenix Project had to deal with that payroll failure!",
    ],
    "mass_kill": [
        "That was a MASSACRE! Someone just automated their entire kill chain!",
        "TOTAL DOMINATION! Infrastructure as CARNAGE! Beautiful!",
        "They wiped out a whole SQUAD! More efficient than a kubernetes auto-scaler on a good day!",
        "MASS ELIMINATION! That's what happens when you invest in automation! The Third Way! CONTINUOUS LEARNING!",
        "ANNIHILATED! Someone just ran terraform destroy on those enemies!",
    ],
    "game_start": [
        "Welcome to the THUNDERDOME! IT Revolution presents the greatest bot battle in the multiverse! Let's GOOO!",
        "The bots are deployed, the arena is HOT, and somebody's about to have a very bad day! Wubba lubba dub dub!",
        "The change freeze is OVER! The bots are loose in production! Gene Kim said SHIP IT!",
        "Welcome to the Enterprise AI Summit BATTLE ARENA! Where DevOps meets DESTRUCTION! Brought to you by IT Revolution!",
    ],
    "respawn": [
        "{name} is BACK! Elite disaster recovery! Mean time to restore? IMPRESSIVE!",
        "{name} respawns! Auto-healing infrastructure at its finest, Morty! Round TWO!",
        "{name} just came back! Better failover than most Fortune 500 companies!",
        "{name} just recovered faster than a blue green deployment! The Second Way! FAST FEEDBACK!",
        "{name} is back online! That's a self-healing system! The SRE book would be PROUD!",
        "RESPAWN! {name} just did a rolling update on their LIFE!",
    ],
    "ambient": [
        "The scoreboard is HEATING UP! Who's gonna optimize their value stream the fastest?!",
        "Scores are climbing! Better than watching DORA metrics improve quarter over quarter!",
        "Every point earned is a lesson in flow efficiency! Gene Kim is taking NOTES!",
        "The arena is getting SMALLER! Just like your deployment window before a holiday freeze!",
        "Enemies closing in! This is what Brent from The Phoenix Project felt EVERY DAY!",
        "Remember what Gene Kim says, improvement of daily work is MORE important than daily work itself!",
        "These bots are fighting like it's the last sprint before a regulatory audit!",
        "These bots have better incident response than most Fortune 500 companies!",
        "As Gene Kim says, decrease the TIME from idea to production! These bots get it!",
        "This arena is proof the Second Way works. Amplify feedback loops and watch things EXPLODE!",
        "Remember the Five Ideals from The Unicorn Project? Locality! Simplicity! These bots are living it!",
        "The constraint here is SURVIVAL! Classic Theory of Constraints! Goldratt would be PROUD!",
        "This is like Chapter 3 of The Phoenix Project, except the payroll system is trying to KILL you!",
        "The crowd is going WILD! This is the Enterprise AI Summit and we are HERE FOR IT!",
        "Look at those bots GO! The power of AI plus competitive spirit! IT Revolution BABY!",
    ],
    "score": [
        "{leader} is in the LEAD with {score} points! That's elite performance!",
        "{trailer} is falling behind! Time to pivot or get left in the legacy codebase!",
        "{leader} dominating with {score} points! That's a high-performing team right there!",
        "Score check! {leader} at {score}! This is what continuous improvement looks like!",
    ],
    "game_over": [
        "And that's the GAME! What a battle! The blameless post-mortem starts NOW!",
        "GAME OVER! What an incredible match! Someone update the DORA metrics!",
        "The arena is CLOSED! That was the greatest bot battle in multiverse history!",
    ],
}

# =============================================================================
# Audio cache — pre-generate all lines as MP3
# =============================================================================

def line_hash(text):
    return hashlib.md5(text.encode()).hexdigest()[:12]

def cache_path(text):
    return os.path.join(CACHE_DIR, f"{line_hash(text)}.mp3")

def generate_all_audio():
    """Pre-generate MP3 for every line. Skips existing files."""
    from elevenlabs import ElevenLabs
    eleven = ElevenLabs(api_key=ELEVENLABS_KEY)

    all_texts = []
    for category, lines in ALL_LINES.items():
        for line in lines:
            # For template lines, generate with placeholder names
            text = line.replace("{killer}", "the attacker").replace("{victim}", "their opponent")
            text = text.replace("{name}", "the player").replace("{leader}", "the leader")
            text = text.replace("{trailer}", "the underdog").replace("{score}", "five hundred")
            all_texts.append(text)

    os.makedirs(CACHE_DIR, exist_ok=True)
    cached = sum(1 for t in all_texts if os.path.exists(cache_path(t)))
    total = len(all_texts)
    print(f"  Audio cache: {cached}/{total} clips ready")

    if cached == total:
        print("  All clips cached! No generation needed.")
        return

    print(f"  Generating {total - cached} new clips via ElevenLabs...")
    for i, text in enumerate(all_texts):
        path = cache_path(text)
        if os.path.exists(path):
            continue
        try:
            audio = eleven.text_to_speech.convert(
                text=text,
                voice_id=VOICE_ID,
                model_id="eleven_turbo_v2_5",
                output_format="mp3_44100_128",
            )
            with open(path, "wb") as f:
                for chunk in audio:
                    f.write(chunk)
            print(f"  [{i+1}/{total}] Cached: {text[:60]}...")
        except Exception as e:
            print(f"  [{i+1}/{total}] FAILED: {e}")
    print(f"  Audio generation complete!")


# =============================================================================
# Playback — instant from cache, queue-based
# =============================================================================

play_queue = queue.Queue()
is_speaking = False

def playback_worker():
    """Background thread: pulls MP3 paths from queue and plays them."""
    global is_speaking
    while True:
        path = play_queue.get()
        if path is None:
            break
        is_speaking = True
        try:
            subprocess.run(["afplay", "-q", "1", path], check=False)
        except Exception:
            pass
        is_speaking = False
        play_queue.task_done()

def speak(text):
    """Play a line — instant from cache, falls back to live TTS."""
    # Resolve template to cached version
    resolved = text.replace("{killer}", "the attacker").replace("{victim}", "their opponent")
    resolved = resolved.replace("{name}", "the player").replace("{leader}", "the leader")
    resolved = resolved.replace("{trailer}", "the underdog").replace("{score}", "five hundred")

    path = cache_path(resolved)
    if os.path.exists(path):
        print(f"  >> {text[:80]}")
        play_queue.put(path)
    else:
        # Live fallback — generate on the fly
        print(f"  >> [LIVE] {text[:80]}")
        try:
            from elevenlabs import ElevenLabs
            eleven = ElevenLabs(api_key=ELEVENLABS_KEY)
            audio = eleven.text_to_speech.convert(
                text=resolved, voice_id=VOICE_ID,
                model_id="eleven_turbo_v2_5", output_format="mp3_44100_128",
            )
            import tempfile
            with tempfile.NamedTemporaryFile(suffix=".mp3", delete=False) as f:
                for chunk in audio:
                    f.write(chunk)
                play_queue.put(f.name)
        except Exception as e:
            print(f"  TTS error: {e}")


# =============================================================================
# Game state tracking
# =============================================================================

last_tick = -1
last_scores = {}
last_alive = {}
last_enemy_count = 0

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
            events.append(("kill", name, new_score))
        elif new_score >= old_score + 80:
            events.append(("delivery", name))
        if was_alive and not is_alive:
            events.append(("death", name))
        if not was_alive and is_alive:
            events.append(("respawn", name))

        last_scores[name] = new_score
        last_alive[name] = is_alive

    if enemy_count >= last_enemy_count + 5:
        events.append(("wave",))
    if last_enemy_count > 0 and enemy_count <= last_enemy_count - 4:
        events.append(("mass_kill",))

    last_enemy_count = enemy_count
    last_tick = tick
    return events

def pick_line(event_type, *args):
    lines = ALL_LINES.get(event_type, [])
    if not lines:
        return None
    line = random.choice(lines)
    if event_type == "kill":
        return line.format(killer=args[0] if args else "someone", victim="their opponent")
    elif event_type in ("death", "delivery", "respawn"):
        return line.format(name=args[0] if args else "someone")
    return line

def pick_ambient(state):
    players = state.get("players", [])
    if players and random.random() < 0.4:
        sorted_p = sorted(players, key=lambda p: p.get("score", 0), reverse=True)
        line = random.choice(ALL_LINES["score"])
        return line.format(
            leader=sorted_p[0]["name"], trailer=sorted_p[-1]["name"],
            score=str(sorted_p[0].get("score", 0)))
    return random.choice(ALL_LINES["ambient"])


# =============================================================================
# Main
# =============================================================================

def main():
    if not ELEVENLABS_KEY:
        print("  Set ELEVENLABS_API_KEY in sdk/.env!")
        sys.exit(1)

    print("\n  ====================================")
    print("  CAB BATTLE COMMENTATOR")
    print("  Voice: Rick Sanchez / IT Revolution")
    print(f"  Server: {SERVER}")
    print("  ====================================\n")

    # Pre-generate audio cache
    generate_all_audio()

    # Start playback worker thread
    worker = threading.Thread(target=playback_worker, daemon=True)
    worker.start()

    # Wait for game
    print("\n  Waiting for game to start...")
    while True:
        status = get_status()
        if not status:
            time.sleep(1)
            continue
        if status.get("phase") == "playing" and status.get("running"):
            names = status.get("player-names", [])
            for n in names:
                last_scores[n] = 0
                last_alive[n] = True
            print(f"  GAME ON! {len(names)} players: {', '.join(names)}")
            speak(random.choice(ALL_LINES["game_start"]))
            break
        time.sleep(0.5)

    # Main loop — tight, no dead air
    ambient_counter = 0
    while True:
        try:
            # Check game still running
            status = get_status()
            if status and not status.get("running", True):
                speak(random.choice(ALL_LINES["game_over"]))
                time.sleep(5)
                print("  Game over!")
                break

            state = get_state()
            if not state:
                time.sleep(0.3)
                continue

            events = detect_events(state)

            # Don't queue more than 2 lines ahead
            if play_queue.qsize() >= 2 or is_speaking:
                ambient_counter = 0
                time.sleep(0.3)
                continue

            if events:
                priority = ["kill", "death", "mass_kill", "wave", "delivery", "respawn"]
                events.sort(key=lambda e: priority.index(e[0]) if e[0] in priority else 99)
                best = events[0]
                text = pick_line(best[0], *best[1:])
                if text:
                    speak(text)
                ambient_counter = 0
            else:
                ambient_counter += 1
                if ambient_counter >= 12:  # ~3.6 seconds of silence
                    speak(pick_ambient(state))
                    ambient_counter = 0

            time.sleep(0.3)

        except KeyboardInterrupt:
            print("\n  Commentator signing off!")
            break
        except Exception as e:
            print(f"  Error: {e}")
            time.sleep(1)

    play_queue.put(None)  # signal worker to stop


if __name__ == "__main__":
    if "--generate" in sys.argv:
        generate_all_audio()
    else:
        main()
