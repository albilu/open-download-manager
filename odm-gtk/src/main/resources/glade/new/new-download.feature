Feature: New Download Dialog
  As a user of the download manager
  I want to create new downloads through the New Download Dialog
  So that I can efficiently manage my downloads with appropriate settings

  Background:
    Given the New Download Dialog is open

  @Requirement-1 @done
  Scenario: URL input deactivates torrent file selection
    When I type a URL in the URL text box
    Then the torrent file selection should be deactivated

  @Requirement-2 @done
  Scenario: Torrent or metalink file selection deactivates URL input
    When I select a torrent or metalink file
    Then the URL text box should be deactivated

  @Requirement-3 @done
  Scenario: Automatic filename proposal from URL
    When I type a URL in the URL text box
    Then the filename should be automatically proposed based on the URL

  @Requirement-4 @done
  Scenario: Disable Filename entry
    When I select a torrent or metalink file
    Then the filename entry should be disabled

  @Requirement-5 @done
  Scenario: Start download button activation
    Given I am creating a new download
    When all required information is valid
    Then the Start download button should be active

  @Requirement-6 @done
  Scenario: Start download button deactivation
    When required information is invalid
    Then the Start download button should be inactive

  @Requirement-7 @done
  Scenario: Cancel button
    When I click the Cancel button
    Then the New Download Dialog should be closed

  @Requirement-8 @done
  Scenario: Close button
    When I click the Close button
    Then the New Download Dialog should be closed

  @Requirement-9 @done
  Scenario: Files listing from torrent, metalink file or magnet link
    Given I am creating a new download from a torrent, metalink file or magnet link
    When I switch to the Files tab
    Then files from the torrent, metalink file or magnet link should be listed (with files structure)
    And a spinner should be displayed if files listing takes too long
    And I should be able to select files to download

  @Requirement-10 @done
  Scenario: Remaining space display for destination folder
    When I select a destination folder
    Then the remaining space should be displayed below the destination folder selection

  @Requirement-11 @done
  Scenario: Default global settings in Options tab
    When I switch to the Options tab
    Then the persisted application global settings should be proposed by default

  @Requirement-12 @done
  Scenario: Modifying default options
    Given I am in the Options tab
    When I change the default options
    Then the changes should be applied to the current download

  @Requirement-13 @done
  Scenario Outline: Download type determination based on rules
    Given I am creating a new download
    When <condition>
    Then the download type should be <expected_type>

    Examples:
      | condition                                                         | expected_type |
      | TOR is enabled and file is not torrent or metalink or magnet link | TOR           |
      | proxy host/port is defined                                        | PROXYCHAINS   |
      | URL is yt-dlp supported                                           | YOUTUBE       |
      | none of the above conditions apply                                | ARIA          |

  @Requirement-14 @done
  Scenario: Download creation and dialog closure
    Given I have configured a new download
    When I create the download
    Then the download should be added to the manager queue
    And the New Download Dialog should be closed
