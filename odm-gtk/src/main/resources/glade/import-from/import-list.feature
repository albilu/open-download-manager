Feature: Import List Dialog
  As a user of the download manager
  I want to create new downloads through the Import List Dialog
  So that I can efficiently manage my downloads with appropriate settings

  Background:
    Given the Import List Dialog is opened with a list of URLs

  @Requirement-1
  Scenario: Filter by extension combo box is poupulated automatically
    Then the extension type combo box should be populated with available file extensions

  @Requirement-2
  Scenario: Filter URLs by extension type
    When I select extension type from the dropdown (e.g., ".txt")
    Then the URLs should be filtered to only activate those with the selected extension

  @Requirement-3
  Scenario: Select/Deselect URLs
    When I select a URL from the list
    Then the URL should be marked as selected
    When I deselect a URL from the list
    Then the URL should be unmarked as selected

  @Requirement-4
  Scenario: Remaining space display for destination folder
    When I select a destination folder
    Then the remaining space should be displayed below the destination folder selection

  @Requirement-5
  Scenario: Start download button activation
    When url list is not empty
    Then the Start download button should be active

  @Requirement-6
  Scenario: Start download button deactivation
    When url list is empty
    Then the Start download button should be inactive

  @Requirement-7
  Scenario: Cancel button
    When I click the Cancel button
    Then the Import List Dialog should be closed

  @Requirement-8
  Scenario: Close button
    When I click the Close button
    Then the Import List Dialog should be closed

  @Requirement-9
  Scenario: Default global settings in Options tab
    When I switch to the Options tab
    Then the persisted application global settings should be proposed by default

  @Requirement-10
  Scenario: Download creation and dialog closure
    Given I have a list of urls
    When I Click Start download
    Then the list of downloads with options should be added to the manager queue
    And the Import List Dialog should be closed

  @Requirement-11
  Scenario Outline: Download type determination based on rules
    Given I Click Start download
    When <condition>
    Then the download type of each url should be <expected_type>

    Examples:
      | condition                                       | expected_type |
      | TOR is enabled and url is not torrent or magnet | TOR           |
      | proxy host/port is defined                      | PROXYCHAINS   |
      | URL is yt-dlp supported                         | YOUTUBE       |
      | none of the above conditions apply              | ARIA          |
