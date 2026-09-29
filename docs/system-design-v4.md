# InvoiceManage 1.3 — System Design

## Runtime and persistent data

The desktop application uses Java Swing and FlatLaf Dark. SQLite stores people, invoices, owners, reimbursement months, and review state. Original PDFs and app settings live beside the database in one work directory. The initial directory is `%LOCALAPPDATA%/LocalInvoiceManager`. The Windows package uses the installed JDK 25 and contains no Java runtime.

`WorkDirectoryService` keeps only the active-directory pointer and a single-instance lock in `%LOCALAPPDATA%/InvoiceManageBootstrap`. These technical bootstrap files stay outside the work directory so the application can locate moved data on the next launch. The work directory itself contains all business data. Existing command-line `--data-root=` and `invoice.data.root` overrides remain available for isolated testing and disable directory changes in Options.

## Changing the work directory

The **设置 → 选项 → 系统** tab displays the active directory and lets the user choose an empty destination. The dialog explains that existing data will be copied and verified, the old directory retained, and the app restarted. It asks for confirmation before starting.

On confirmation, the app saves settings, launches a replacement process with the destination path, and exits. The replacement waits for the current process's single-instance lock, ensuring database writes from this process have ended. It rejects a nonempty destination or a path that contains, or is contained by, the current work or bootstrap directory. It also checks resolved paths so a symbolic link cannot accidentally route the copy into the source.

The replacement copies every regular file and directory from the old work directory. It rejects special files and symbolic links, verifies the byte length and SHA-256 hash of each copied file, checks the file count, then runs SQLite `PRAGMA integrity_check` on the copied database. Only after all checks pass does it atomically update the active-directory pointer and open the UI from the new directory. The source is never deleted. If migration fails, the pointer remains on the source; any partial destination is left intact for inspection and must be cleared or replaced by the user before retrying.

Normal application upgrades replace code and packaged OCR files only. They do not clear or recreate a selected work directory. The explicit **恢复备份** command remains a separate, confirmed operation that may replace its contents.

## Batch ownership

The pool table supports Ctrl and Shift multi-selection. Its action button shows the selection count. Right-clicking a selected row preserves the multi-selection; right-clicking another row selects that row. Multiple selected invoices expose **批量归属于** and disable actions that apply to one invoice.

The owner picker reuses the recent-four and all-people avatar sections. For a batch, it asks for one reimbursement month, defaulting to the shared existing month when possible. `AppService.assignMany` validates the owner, month, and distinct invoice IDs. `Database.assignInvoices` updates all selected invoices and the recent-owner entry in one SQLite transaction. If any invoice is missing or any statement fails, the transaction rolls back so there is no partial assignment.

## Pool ordering and visual state

`PoolPanel.DISPLAY_ORDER` puts invoices without an owner first, followed by assigned invoices; each group is ordered by newest import. The table uses distinct dark-theme row colors and an owner marker: `● 未归属` for unassigned and `✓ 人员名` for assigned. Selected rows keep the standard selection highlight. Search and filters preserve this order.

## Verification

Automated tests cover copying an existing work directory, preserving unknown future files and the original directory, rejecting nonempty or nested destinations, and retaining the old pointer when database integrity fails. Assignment tests verify all-or-nothing batch behavior and a shared reimbursement month. Pool tests verify ordering, markers, row color distinction, and multi-selection. The prior OCR, AI, reimbursement, backup, and real-invoice regressions remain in the suite.

The migration process is not invoked against a user's live invoice directory during testing. The full copied dataset is verified before activation; the old directory remains available as a recovery copy. The app cannot prevent a different, older version that does not use the single-instance lock from writing to the old directory concurrently, so users should close older running versions before launching 1.3.
