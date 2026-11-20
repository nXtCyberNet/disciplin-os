import sys
import time
import argparse
import logging
import threading
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from dispatcher import MissionDispatcher
from web_server import run_server

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    datefmt="%H:%M:%S"
)
logger = logging.getLogger("Main")

def background_poll_loop(dispatcher: MissionDispatcher):
    """Periodically queries GitHub and checks phone mission obligations."""
    logger.info("Background poll worker started.")
    while True:
        try:
            dispatcher.poll_cycle()
        except Exception as e:
            logger.error(f"Error in background poll loop: {e}")

        interval = dispatcher.config.get("poll_interval_seconds", 60)
        time.sleep(max(10, interval))

def main():
    parser = argparse.ArgumentParser(description="Discipline Mission Dispatcher (PC/Server Automation)")
    parser.add_argument("--port", type=int, default=5050, help="Web dashboard port (default: 5050)")
    parser.add_argument("--check-now", action="store_true", help="Run rule evaluation once and exit")
    parser.add_argument("--trigger", type=str, help="Manually dispatch webhook for mission title or ID and exit")
    args = parser.parse_args()

    dispatcher = MissionDispatcher()

    if args.trigger:
        logger.info(f"Manually triggering mission '{args.trigger}'...")
        success, msg = dispatcher.trigger_phone_mission(args.trigger, source="CLI Trigger")
        print(f"Result: {'SUCCESS' if success else 'FAILED'} - {msg}")
        sys.exit(0 if success else 1)

    if args.check_now:
        logger.info("Running manual one-time rule evaluation...")
        results = dispatcher.evaluate_rules()
        print(f"Evaluation complete. Matched: {len(results)}")
        for r in results:
            print(f" - {r['rule']}: {r['event']} -> '{r['mission']}' ({r['status']})")
        sys.exit(0)

    # Start background polling thread
    poll_thread = threading.Thread(target=background_poll_loop, args=(dispatcher,), daemon=True)
    poll_thread.start()

    print("=================================================================")
    print(" 🚀 DISCIPLINE MISSION DISPATCHER IS RUNNING!")
    print(f" 🌐 Web Dashboard: http://localhost:{args.port}")
    print(f" 📱 Phone URL:     {dispatcher.config.get('phone_url')}")
    print(f" 🐙 GitHub User:   {dispatcher.config.get('github_user')}")
    print("=================================================================")

    # Run Web Server on main thread
    run_server(dispatcher, port=args.port)

if __name__ == "__main__":
    main()
