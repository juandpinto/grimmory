import {
  Component,
  DestroyRef,
  inject,
  Input,
  OnChanges,
  OnInit,
  signal,
  SimpleChanges
} from '@angular/core';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {FormsModule} from '@angular/forms';
import {ConfirmationService, MessageService} from 'primeng/api';
import {Button} from 'primeng/button';
import {TableModule} from 'primeng/table';
import {Dialog} from 'primeng/dialog';
import {DatePicker} from 'primeng/datepicker';
import {Textarea} from 'primeng/textarea';
import {ProgressSpinner} from 'primeng/progressspinner';
import {ConfirmDialog} from 'primeng/confirmdialog';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {ReadthroughService} from '../../../../stats/component/reading-year/service/readthrough.service';
import {BookReadthroughDto} from '../../../../stats/component/reading-year/model/readthrough.model';

@Component({
  selector: 'app-book-reading-history',
  standalone: true,
  imports: [
    FormsModule,
    Button,
    TableModule,
    Dialog,
    DatePicker,
    Textarea,
    ProgressSpinner,
    ConfirmDialog,
    TranslocoDirective,
  ],
  providers: [ConfirmationService],
  templateUrl: './book-reading-history.component.html',
  styleUrls: ['./book-reading-history.component.scss'],
})
export class BookReadingHistoryComponent implements OnInit, OnChanges {
  @Input() bookId!: number;

  private readonly readthroughService = inject(ReadthroughService);
  private readonly confirmationService = inject(ConfirmationService);
  private readonly messageService = inject(MessageService);
  private readonly t = inject(TranslocoService);
  private readonly destroyRef = inject(DestroyRef);

  readthroughs = signal<BookReadthroughDto[]>([]);
  loading = signal(false);
  showForm = false;

  formStartedOn: Date | null = null;
  formFinishedOn: Date | null = null;
  formNotes: string = '';
  editingId: number | null = null;

  get formDialogTitle(): string {
    return this.editingId != null
      ? this.t.translate('metadata.history.form.title') + ' (edit)'
      : this.t.translate('metadata.history.form.title');
  }

  ngOnInit(): void {
    this.loadReadthroughs();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['bookId'] && !changes['bookId'].firstChange) {
      this.loadReadthroughs();
    }
  }

  loadReadthroughs(): void {
    this.loading.set(true);
    this.readthroughService.getReadthroughs(this.bookId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (data) => {
          this.readthroughs.set(data);
          this.loading.set(false);
        },
        error: () => {
          this.readthroughs.set([]);
          this.loading.set(false);
        }
      });
  }

  openForm(rt?: BookReadthroughDto): void {
    if (rt) {
      this.editingId = rt.id;
      this.formStartedOn = rt.startedOn ? new Date(rt.startedOn + 'T12:00:00') : null;
      this.formFinishedOn = new Date(rt.finishedOn + 'T12:00:00');
      this.formNotes = rt.notes ?? '';
    } else {
      this.editingId = null;
      this.formStartedOn = null;
      this.formFinishedOn = null;
      this.formNotes = '';
    }
    this.showForm = true;
  }

  closeForm(): void {
    this.showForm = false;
  }

  saveReadthrough(): void {
    if (!this.formFinishedOn) return;

    const request = {
      startedOn: this.formStartedOn ? this.toIsoDate(this.formStartedOn) : null,
      finishedOn: this.toIsoDate(this.formFinishedOn),
      notes: this.formNotes.trim() || null,
    };

    const op = this.editingId != null
      ? this.readthroughService.updateReadthrough(this.bookId, this.editingId, request)
      : this.readthroughService.createReadthrough(this.bookId, request);

    op.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.closeForm();
        this.loadReadthroughs();
      },
      error: () => {
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('common.error'),
          detail: this.t.translate('common.genericError'),
        });
      }
    });
  }

  confirmDelete(rt: BookReadthroughDto): void {
    this.confirmationService.confirm({
      message: this.t.translate('metadata.history.deleteConfirm'),
      header: this.t.translate('metadata.history.deleteConfirmHeader'),
      icon: 'pi pi-exclamation-triangle',
      acceptButtonStyleClass: 'p-button-danger',
      accept: () => this.deleteReadthrough(rt),
    });
  }

  private deleteReadthrough(rt: BookReadthroughDto): void {
    this.readthroughService.deleteReadthrough(this.bookId, rt.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => this.loadReadthroughs(),
        error: () => {
          this.messageService.add({
            severity: 'error',
            summary: this.t.translate('common.error'),
            detail: this.t.translate('common.genericError'),
          });
        }
      });
  }

  formatDate(isoDate: string): string {
    return new Date(isoDate + 'T12:00:00').toLocaleDateString(undefined, {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
    });
  }

  private toIsoDate(date: Date): string {
    const y = date.getFullYear();
    const m = String(date.getMonth() + 1).padStart(2, '0');
    const d = String(date.getDate()).padStart(2, '0');
    return `${y}-${m}-${d}`;
  }
}
