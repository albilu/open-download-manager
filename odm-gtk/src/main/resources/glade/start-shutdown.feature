Feature: Startup and Shutdown Dialog
  As a user of the download manager
  I want to view information about the application startup and shutdown
  So that I can understand its features and capabilities

  @Requirement-1 @done
  Scenario: Show Startup Dialog
    Given the application is starting
    Then the startup dialog is shown
    And I should see the application logo
    And I should see messages and progress indicators

  @Requirement-2 @done
  Scenario: Show Shutdown Dialog
    Given the application is shutting down
    Then the shutdown dialog is shown
    And I should see messages indicating the shutdown process
    And I should see a progress bar indicating the shutdown status
