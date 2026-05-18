Feature: Settings Dialog
  As a user of the download manager
  I want to configure the application settings
  So that I can customize its behavior to my liking

  Background:
    Given the Settings Dialog is open

  @Requirement-1
  Scenario: Adjusting Download Settings
    When I change the maximum download speed to 500 KB/s
    And I change the number of connections per server to 4
    And I change the download directory to "/home/user/Downloads"
    And I click the "Apply" button
    Then the settings should be saved successfully
    And applied to type-specific settings

  @Requirement-2
  Scenario: Canceling Changes
    When I change the maximum download speed to 500 KB/s
    And I change the number of connections per server to 4
    And I change the download directory to "/home/user/Downloads"
    And I click the "Cancel" button
    Then the settings should be discarded

  @Requirement-3
  Scenario: Resetting to Default Settings
    When I click the "Reset" button
    Then the settings should be reset to their default values
    And applied to type-specific settings

  @Requirement-4
  Scenario: Applying Changes
    When I click the "Apply" button
    Then any changes to the settings should be saved successfully
    And applied to type-specific settings
