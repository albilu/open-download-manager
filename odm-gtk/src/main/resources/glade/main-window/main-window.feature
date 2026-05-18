Feature: Main Window
  As a user of the download manager
  I want to see the main window
  So that I can manage my downloads

  Background:
  The main window is the primary interface for the download manager.
  It displays the list of current downloads and their status.
  The user can start, pause, and cancel downloads from this window.

  @Requirement-1 @done
  Scenario: Main window is displayed
    Given the download manager is running
    When the user opens the application
    Then the main window is displayed

  @Requirement-2 @done
  Scenario: Download count per status is displayed
    Given the download manager is running
    When the user opens the application
    Then the download count per status is displayed based on the download list
      | Status     | Count   |
      | ---------- | ------- |
      | All Status |      12 |
      | Active     |       1 |
      | Queued     |       5 |
      | Finished   |       3 |
      | Deleted    |       3 |
    And the download count is updated when a download is added, removed, or its status changes

  @Requirement-3 @done
  Scenario: Download count per category is displayed
    Given the download manager is running
    When the user opens the application
    Then the download count per category is displayed based on the download list
      | Category       | Count   |
      | ----------     | ------- |
      | All Categories |      12 |
      | Videos         |       1 |
      | Audios         |       5 |
      | Photos         |       3 |
      | Programs       |       3 |
      | Other          |       0 |
    And the download count is updated when a download is added, removed, or its status changes

  @Requirement-4 @done
  Scenario: Display download list filtered by Status
    Given the download manager is running
    When the user opens the application
    And the user select a status
    Then the download list is displayed filtered by the selected status

  @Requirement-5 @done
  Scenario: Display download list filtered by Category
    Given the download manager is running
    When the user opens the application
    And the user select a category
    Then the download list is displayed filtered by the selected category

  @Requirement-6 @done
  Scenario: Status bar is displayed
    Given the download manager is running
    When the user opens the application
    Then the status bar is displayed with the global progress bar and the global speed indicator
    And the global progress bar is live updated with the total progress of all downloads
    And the global speed indicator is live updated with the current download speed
    And the status bar is live updated with the DHT nodes count
    And important messages are displayed to the user in the info_label

  @Requirement-7 @done
  Scenario: Search downloads by name
    Given the download manager is running
    When the user opens the application
    And the user enters a search term
    Then the download list is displayed filtered by the search term
    And the search results are displayed in the download list

  @Requirement-8 @done
  Scenario: Clear search results
    Given the download manager is running
    When the user opens the application
    And the user enters a search term
    And the user clears the search term
    Then the download list is displayed with all downloads

  @Requirement-9 @done
  Scenario: No search results found
    Given the download manager is running
    When the user opens the application
    And the user enters a search term that matches no downloads
    Then the download list is displayed with no results

  @Requirement-10
  Scenario: Display selected download information
    Given the download manager is running
    When the user opens the application
    And the user selects a download from the list
    And the info panel is opened
    Then the selected download's information is displayed in each tab when the user switches between them
      | Tabs       | Description                            |
      | ---------- | -------                                |
      | General    | general information about the download |
      | Trackers   | applicable to torrent downloads        |
      | Peers      | applicable to torrent downloads        |
      | Files      | display files                          |

  @Requirement-11 @done
  Scenario: Right-click context menu is displayed
    Given the download manager is running
    When the user opens the application
    And the user right-clicks on a download in the list
    Then the right-click context menu is displayed
    And the context menu contains options to resume, pause the download etc.

  @Requirement-12 @done
  Scenario: Add new download
    Given the download manager is running
    When the user clicks the add button
    Then the new download dialog is displayed
    And the user enters the download URL
    And the user clicks the Start Download button
    Then the download is added to the download manager queue
    And the download is displayed in the download list view

  @Requirement-14 @done
  Scenario: Pause a download
    Given the download manager is running
    When the user selects a download in the list
    And the user clicks the pause button
    Then the download is paused
    And the download status is updated to paused

  @Requirement-15 @done
  Scenario: Resume a download
    Given the download manager is running
    When the user selects a download in the list
    And the user clicks the resume button
    Then the download is resumed
    And the download status is updated to downloading

  @Requirement-16 @done
  Scenario: Delete a download
    Given the download manager is running
    When the user selects a download in the list
    And the user clicks the delete button
    Then the download is stopped and removed from the download manager
    And the download is no longer displayed in the download list

  @Requirement-17 @done
  Scenario: Move up a download
    Given the download manager is running
    When the user selects a download in the list
    And the user clicks the move up button
    Then the selected download is moved up in the download manager queue
    And the download list is updated to reflect the change

  @Requirement-18 @done
  Scenario: Move down a download
    Given the download manager is running
    When the user selects a download in the list
    And the user clicks the move down button
    Then the selected download is moved down in the download manager queue
    And the download list is updated to reflect the change

  @Requirement-19 @done
  Scenario: Move a download to top
    Given the download manager is running
    When the user selects a download in the list
    And the user clicks the move to top button
    Then the selected download is moved to the top of the download manager queue
    And the download list is updated to reflect the change

  @Requirement-20 @done
  Scenario: Move a download to bottom
    Given the download manager is running
    When the user selects a download in the list
    And the user clicks the move to bottom button
    Then the selected download is moved to the bottom of the download manager queue
    And the download list is updated to reflect the change

  @Requirement-21 @done
  Scenario: Open download folder action
    Given the download manager is running
    When the user selects a download in the list
    And the user clicks the open folder button
    Then the download folder is opened in the file system

  @Requirement-22 @done
  Scenario: Copy magnet link action
    Given the download manager is running
    When the user selects a torrent download in the list
    And the user clicks the copy magnet link button
    Then the magnet link is copied to the clipboard

  @Requirement-23 @done
  Scenario: Show download properties action
    Given the download manager is running
    When the user selects a download in the list
    And the user clicks the properties button
    Then the download properties dialog is displayed

  @Requirement-24
  Scenario: Change download destination action
    Given the download manager is running
    When the user selects a download in the list
    And the user clicks the change destination button
    Then a file chooser dialog should be opened
    And the user should be able to select a new download destination
    And the selected download destination should be updated

  @Requirement-25
  Scenario: Verify data integrity action
    Given the download manager is running
    When the user selects a download in the list
    And the user clicks the verify data button
    Then the data integrity check is performed
    And the user is notified of the result

  @Requirement-26 @done
  Scenario: Delete a download with files action
    Given the download manager is running
    When the user clicks the delete button
    Then the download is removed from the download manager queue
    And the download is no longer displayed in the download list
    And the download files are deleted from the disk

  @Requirement-27 @done
  Scenario: Settings button
    Given the download manager is running
    When the user clicks the settings button
    Then the settings dialog is displayed with the persisted application settings

  @Requirement-28 @done
  Scenario: Tor button
    Given the download manager is running
    When the user clicks the Tor button
    Then tor service is started
    And Tor is enabled

  @Requirement-29
  Scenario: Offline menu action
    Given the download manager is running
    When the user clicks the offline button
    Then the download manager should switch to offline mode
    And all downloads should be paused

  @Requirement-30 @done
  Scenario: Exit menu action
    Given the download manager is running
    When the user clicks the exit button
    Then the user should be prompted to confirm exit
    And all downloads should be paused
    And the download manager should shutdown

  @Requirement-31 @done
  Scenario: Clipboard Import menu action
    Given the download manager is running
    When the user clicks the clipboard import button
    Then the clipboard contents should be imported as new url lists
    And the Import list dialog should be opened

  @Requirement-32 @done
  Scenario: Import URL Sequence menu action
    Given the download manager is running
    When the user clicks the import URL sequence button
    Then Import Sequence dialog should be opened

  @Requirement-33 @done
  Scenario: Import From Text File menu action
    Given the download manager is running
    When the user clicks the import from file button
    Then a file chooser dialog should be opened
    And the user should be able to select a text file to import
    And the selected file should be imported as a new url list
    And the Import List dialog should be opened

  @Requirement-34 @done
  Scenario: Export To File menu action
    Given the download manager is running
    When the user clicks the export to file button
    Then a file chooser dialog should be opened
    And the user should be able to select a location to save the file
    And the current download list urls should be exported to the selected location

  @Requirement-35 @done
  Scenario: Import from HTML file menu action
    Given the download manager is running
    When the user clicks the import from HTML file button
    Then a file chooser dialog should be opened
    And the user should be able to select an HTML file to import
    And the selected HTML file should be parsed and imported as a new url list in Import List dialog

  @Requirement-36 @done
  Scenario: Enable clipboard monitoring menu checkbox
    Given the download manager is running
    When the user enables clipboard monitoring checkbox
    Then clipboard monitoring should be enabled
    And the clipboard contents should be monitored for new URLs
    And any new URLs should be automatically added to the download list

  @Requirement-37 @done
  Scenario: Disable clipboard monitoring menu checkbox
    Given the download manager is running
    When the user disable clipboard monitoring checkbox
    Then clipboard monitoring should be disabled

  @Requirement-38
  Scenario: Enable Silent Mode
    Given the download manager is running
    And the user has enabled clipboard monitoring
    When the user clicks the enable silent mode checkbox
    Then silent mode should be enabled

  @Requirement-39
  Scenario: Disable Silent Mode
    Given the download manager is running
    And the user has enabled clipboard monitoring
    When the user clicks the enable silent mode checkbox
    Then silent mode should be disabled

  @Requirement-40
  Scenario: Settings Edit menu action
    Given the download manager is running
    When the user clicks the settings edit button
    Then the settings dialog should be displayed

  @Requirement-41
  Scenario: Left Panel View Menu checkbox
    Given the download manager is running
    When the user clicks the left panel view menu checkbox
    Then the left panel view menu should be displayed/hidden accordingly

  @Requirement-42
  Scenario: Info Panel View Menu checkbox
    Given the download manager is running
    When the user clicks the Info panel view menu checkbox
    Then the Info panel view menu should be displayed/hidden accordingly

  @Requirement-43
  Scenario: Columns View Menu checkbox
    Given the download manager is running
    When the user toggles the download list columns
    Then the download list Columns should be displayed/hidden accordingly

  @Requirement-44
  Scenario: Download Menu items
    Given the download manager is running
    When the user interacts with the download menu items
    Then the appropriate actions should be taken
