Feature: Import Sequence Dialog
  As a user of the download manager
  I want to create new downloads through the Import Sequence Dialog
  So that I can efficiently manage my downloads with appropriate settings

  Background:
    Given the Import Sequence Dialog is open

  @Requirement-1
  Scenario: Sequenced number URLs generation with custom prefix and suffix
    When I type a URL in the URL text box (ex: http://example.com/images/prefix-{num}-suffix.jpg)
    And I set Num from 1 to 10 with 3 digits
    Then the sequence URLs should be generated (ex: http://example.com/images/prefix-001-suffix.jpg, http://example.com/images/prefix-002-suffix.jpg, ..., http://example.com/images/prefix-010-suffix.jpg)
    Then the sequence URLs should be displayed in the list

  @Requirement-2
  Scenario: Sequenced character URLs generation with custom prefix and no suffix
    When I type a URL in the URL text box (ex: http://example.com/images/prefix-{char})
    And I set Char from a to j with 1 character
    Then the sequence URLs should be generated (ex: http://example.com/images/prefix-a, http://example.com/images/prefix-b, ..., http://example.com/images/prefix-j)
    Then the sequence URLs should be displayed in the list
    #
    # Scenario: Sequenced number and char URLs generation with custom prefix and no suffix
    #     When I type a URL in the URL text box (ex: http://example.com/images/prefix-{char}-{num})
    #     And I set Num from 1 to 10 with 3 digits
    #     And I set Char from a to b
    #     Then the sequence URLs should be generated
    #     (ex: http://example.com/images/prefix-a-001, http://example.com/images/prefix-a-002, ..., http://example.com/images/prefix-a-010 and http://example.com/images/prefix-b-001, http://example.com/images/prefix-b-002, ..., http://example.com/images/prefix-b-010)
    #     And the sequence URLs should be displayed in the list

  @Requirement-3
  Scenario: Remaining space display for destination folder
    When I select a destination folder
    Then the remaining space should be displayed below the destination folder selection

  @Requirement-4
  Scenario: Start download button activation
    When sequenced url list is not empty
    Then the Start download button should be active

  @Requirement-5
  Scenario: Start download button deactivation
    When sequenced url list is empty
    Then the Start download button should be inactive

  @Requirement-6
  Scenario: Cancel button
    When I click the Cancel button
    Then the Import Sequence Dialog should be closed

  @Requirement-7
  Scenario: Close button
    When I click the Close button
    Then the Import Sequence Dialog should be closed

  @Requirement-8
  Scenario: Default global settings in Options tab
    When I switch to the Options tab
    Then the persisted application global settings should be proposed by default

  @Requirement-9
  Scenario: Download creation and dialog closure
    Given I have a sequence of urls
    When I Click Start download
    Then the list of downloads with options should be added to the manager queue
    And the Import Sequence Dialog should be closed

  @Requirement-10
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
