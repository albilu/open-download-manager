# Notes: Download Properties, Options, all refer to Download settings in the context of a download item.
Feature: Download Item Property Dialog
  As a user of the download manager
  I want to view and edit the properties (download settings) of a download item
  So that I can manage my downloads more effectively

  Background:
    Given the Download Item Property Dialog is opened for a specific download item

  @Requirement-1
  Scenario: View download item properties
    Then I should see the properties of the selected download item

  @Requirement-2
  Scenario: Prevent edit when download is completed
    Given the selected download item is completed
    When I try to edit the properties
    Then I should not be able to edit the properties

  @Requirement-3
  Scenario: Edit download item properties
    When I modify the properties of the download item
    And I click the Apply button
    Then the properties should be updated in the download manager for the selected download item
    Then download should continue (if was on going) with the updated properties

  @Requirement-4
  Scenario: Cancel button
    When I click the Cancel button
    Then the Download Item Property Dialog should be closed

  @Requirement-5
  Scenario: Ok button
    When I click the Ok button
    Then the Download Item Property Dialog should be closed

  @Requirement-6
  Scenario: Close button
    When I click the Close button
    Then the Download Item Property Dialog should be closed
