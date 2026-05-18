<!-- Use this file to provide workspace-specific custom instructions to Copilot. For more details, visit https://code.visualstudio.com/docs/copilot/copilot-customization#_use-a-githubcopilotinstructionsmd-file -->

This project is a Java 21 Maven-based native Linux download manager.
Use modular architecture and prioritize performance, memory efficiency, and stability.
Based on aria2, yt-dlp, and httrack.
Follow the README for development steps and features.

Instructions:
The glade file describing the UI design and layout are now completed.! Don't modify the layout or design. You can align widgets identification and signals if needed

DON'T LEAVE METHODS UNIMPLEMENTED!

Logging:

-   structured messages (context tracking) where appropriate
-   log levels appropriately (e.g., debug, info, warn, error)

Testing Considerations:

-   Considering that tests will be run in a specialized docker container containing all dependencies.
-   AVOID MOCK on critical parts of the core.
-   focusing on testing the core engine implementation rather than front-end interactions
-   Avoid bypass (assertTrue (true))

Dont run tests!! Verify code syntax is correct by compiling (make compile)!
make run to run app
Don't write readme, example, demos!
