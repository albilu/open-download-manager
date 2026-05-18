Feature: About Dialog
  As a user of the download manager
  I want to view information about the application
  So that I can understand its features and capabilities

  @Requirement-1 @done
  Scenario: Open About Dialog
    Given the user is on the main window
    When the user selects "About" from the menu
    Then the About Dialog should be displayed

  @Requirement-2 @done
  Scenario: Close About Dialog
    Given the About Dialog is open
    When the user clicks the "Close" button
    Then the About Dialog should be closed
