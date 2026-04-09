#!/usr/bin/env python3
"""Generate audio manifest mapping event types to cached MP3 hashes."""
import json, hashlib

def resolve(text):
    return (text
        .replace('{killer}', 'the attacker').replace('{victim}', 'their opponent')
        .replace('{name}', 'the player').replace('{leader}', 'the leader')
        .replace('{trailer}', 'the underdog').replace('{score}', 'five hundred'))

def line_hash(text):
    return hashlib.md5(text.encode()).hexdigest()[:12]

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
    "wipeout": [
        "TOTAL WIPEOUT! Every single bot is DOWN! The arena is EMPTY! This is a full production outage!",
        "EVERYBODY IS DEAD, Morty! The entire cluster just went down! Who forgot to set up the health checks?!",
        "Complete system failure! Zero bots standing! This is what happens when NOBODY reads the runbook!",
    ],
    "game_over": [
        "And that's the GAME! What a battle! The blameless post-mortem starts NOW!",
        "GAME OVER! What an incredible match! Someone update the DORA metrics!",
        "The arena is CLOSED! That was the greatest bot battle in multiverse history!",
    ],
}

manifest = {}
for category, lines in ALL_LINES.items():
    manifest[category] = []
    for line in lines:
        resolved = resolve(line)
        h = line_hash(resolved)
        manifest[category].append(h)

with open("../battle-cab/resources/public/audio/manifest.json", "w") as f:
    json.dump(manifest, f, indent=2)

total = sum(len(v) for v in manifest.values())
print(f"Manifest: {total} clips in {len(manifest)} categories: {list(manifest.keys())}")
