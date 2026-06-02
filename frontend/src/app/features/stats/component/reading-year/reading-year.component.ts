import {
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {DecimalPipe} from '@angular/common';
import {FormsModule} from '@angular/forms';
import {Router} from '@angular/router';
import {BaseChartDirective, provideCharts, withDefaultRegisterables} from 'ng2-charts';
import {ChartConfiguration, ChartData} from 'chart.js';
import {injectQuery, QueryClient} from '@tanstack/angular-query-experimental';
import {lastValueFrom} from 'rxjs';
import {ConfirmationService, MessageService} from 'primeng/api';
import {AutoComplete} from 'primeng/autocomplete';
import {Button} from 'primeng/button';
import {DatePicker} from 'primeng/datepicker';
import {Dialog} from 'primeng/dialog';
import {InputNumber} from 'primeng/inputnumber';
import {ProgressSpinner} from 'primeng/progressspinner';
import {ConfirmDialog} from 'primeng/confirmdialog';
import {Textarea} from 'primeng/textarea';
import {Tooltip} from 'primeng/tooltip';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {BookService} from '../../../book/service/book.service';
import {Book} from '../../../book/model/book.model';
import {UrlHelperService} from '../../../../shared/service/url-helper.service';
import {UserService} from '../../../settings/user-management/user.service';
import {BookDialogHelperService} from '../../../book/components/book-browser/book-dialog-helper.service';
import {ReadingGoalService} from './service/reading-goal.service';
import {ReadthroughService} from './service/readthrough.service';
import {YearlySummaryResponse} from './model/reading-goal.model';
import {BookReadthroughRequest, ReadthroughSummaryDto} from './model/readthrough.model';

const MONTH_LABELS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

@Component({
  selector: 'app-reading-year',
  standalone: true,
  imports: [
    DecimalPipe,
    FormsModule,
    AutoComplete,
    BaseChartDirective,
    Button,
    DatePicker,
    Dialog,
    InputNumber,
    ProgressSpinner,
    ConfirmDialog,
    Textarea,
    Tooltip,
    TranslocoDirective,
  ],
  providers: [ConfirmationService, provideCharts(withDefaultRegisterables())],
  templateUrl: './reading-year.component.html',
  styleUrls: ['./reading-year.component.scss'],
})
export class ReadingYearComponent {
  private readonly goalService = inject(ReadingGoalService);
  private readonly readthroughService = inject(ReadthroughService);
  private readonly bookService = inject(BookService);
  private readonly urlHelper = inject(UrlHelperService);
  private readonly router = inject(Router);
  private readonly confirmationService = inject(ConfirmationService);
  private readonly messageService = inject(MessageService);
  private readonly t = inject(TranslocoService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly queryClient = inject(QueryClient);
  private readonly userService = inject(UserService);
  private readonly bookDialogHelper = inject(BookDialogHelperService);

  private readonly metadataCenterViewMode = computed(() =>
    this.userService.currentUser()?.userSettings?.metadataCenterViewMode ?? 'route'
  );

  private readonly failedCoverIds = signal(new Set<number>());

  readonly currentYear = new Date().getFullYear();
  year = signal(this.currentYear);

  private readonly summaryQuery = injectQuery(() => ({
    queryKey: ['reading-year-summary', this.year()] as const,
    queryFn: () => lastValueFrom(this.goalService.getYearlySummary(this.year())),
  }));

  readonly loading = computed(() => this.summaryQuery.isPending());
  readonly summary = computed(() => this.summaryQuery.data() ?? null);

  constructor() {
    effect(() => {
      const data = this.summary();
      if (data) untracked(() => this.updateChart(data));
    });
  }

  // Goal dialog
  showGoalDialog = false;
  goalInput: number = 0;

  // Log Read dialog
  showLogReadDialog = false;
  logReadOption: {book: Book; label: string} | null = null;
  logReadFilteredOptions: {book: Book; label: string}[] = [];
  logReadStartedOn: Date | null = null;
  logReadFinishedOn: Date | null = null;
  logReadNotes = '';

  // Chart config
  readonly chartType = 'bar' as const;
  chartData = signal<ChartData<'bar', number[], string>>({labels: [], datasets: []});
  readonly chartOptions: ChartConfiguration['options'] = {
    responsive: true,
    maintainAspectRatio: false,
    plugins: {
      legend: {display: false},
      tooltip: {
        enabled: true,
        callbacks: {
          label: (ctx) => `${ctx.parsed.y} book${ctx.parsed.y === 1 ? '' : 's'}`,
        },
      },
      datalabels: {display: false},
    },
    scales: {
      x: {
        grid: {display: false},
        border: {display: false},
        ticks: {font: {family: "'Inter', sans-serif", size: 11}},
      },
      y: {
        beginAtZero: true,
        ticks: {
          stepSize: 1,
          font: {family: "'Inter', sans-serif", size: 11},
        },
        grid: {},
        border: {display: false},
      },
    },
  };

  // Computed helpers
  readonly goal = computed(() => this.summary()?.goal ?? null);
  readonly booksRead = computed(() => this.summary()?.booksRead ?? 0);
  readonly totalPages = computed(() => this.summary()?.pagesRead ?? 0);
  readonly currentPace = computed(() => this.summary()?.expectedByPace ?? 0);
  readonly readthroughs = computed(() => this.summary()?.readthroughs ?? []);
  readonly isCurrentYear = computed(() => this.year() === this.currentYear);

  readonly goalProgress = computed(() => {
    const g = this.goal();
    if (!g) return 0;
    return Math.min(1, this.booksRead() / g);
  });

  readonly goalProgressPct = computed(() =>
    Math.round(this.goalProgress() * 100)
  );

  readonly paceStatus = computed((): 'on' | 'ahead' | 'behind' | 'none' => {
    const g = this.goal();
    if (!g) return 'none';
    if (!this.isCurrentYear()) return 'none';
    const pace = this.currentPace();
    const read = this.booksRead();
    if (read >= pace - 0.5) return read >= pace + 0.5 ? 'ahead' : 'on';
    return 'behind';
  });

  readonly circumference = 2 * Math.PI * 52;
  readonly dashOffset = computed(() =>
    this.circumference * (1 - this.goalProgress())
  );

  previousYear(): void {
    this.year.update(y => y - 1);
    this.loadSummary();
  }

  nextYear(): void {
    if (this.year() < this.currentYear) {
      this.year.update(y => y + 1);
      this.loadSummary();
    }
  }

  loadSummary(): void {
    void this.queryClient.invalidateQueries({
      queryKey: ['reading-year-summary', this.year()],
    });
  }

  private updateChart(data: YearlySummaryResponse): void {
    const counts = new Array(12).fill(0);
    for (const m of data.monthlyBreakdown) {
      if (m.month >= 1 && m.month <= 12) {
        counts[m.month - 1] = m.count;
      }
    }
    this.chartData.set({
      labels: MONTH_LABELS,
      datasets: [{
        data: counts,
        backgroundColor: 'rgba(var(--p-primary-500-rgb, 99,102,241), 0.75)',
        borderColor: 'rgba(var(--p-primary-500-rgb, 99,102,241), 1)',
        borderWidth: 1,
        borderRadius: 4,
        barPercentage: 0.7,
        categoryPercentage: 0.8,
      }],
    });
  }

  openGoalDialog(): void {
    this.goalInput = this.goal() ?? 0;
    this.showGoalDialog = true;
  }

  saveGoal(): void {
    if (!this.goalInput || this.goalInput < 1) return;
    this.goalService.setGoal(this.year(), {bookGoal: this.goalInput})
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.showGoalDialog = false;
          this.loadSummary();
        },
        error: () => {
          this.messageService.add({
            severity: 'error',
            summary: this.t.translate('common.error'),
            detail: this.t.translate('common.genericError'),
          });
        },
      });
  }

  confirmDeleteGoal(): void {
    this.confirmationService.confirm({
      message: this.t.translate('readingYear.goal.deleteConfirm', {year: this.year()}),
      header: this.t.translate('readingYear.goal.deleteConfirmHeader'),
      icon: 'pi pi-exclamation-triangle',
      acceptButtonStyleClass: 'p-button-danger',
      accept: () => this.deleteGoal(),
    });
  }

  private deleteGoal(): void {
    this.goalService.deleteGoal(this.year())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({next: () => this.loadSummary()});
  }

  formatDate(isoDate: string): string {
    return new Date(isoDate + 'T12:00:00').toLocaleDateString(undefined, {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
    });
  }

  getCoverUrl(book: ReadthroughSummaryDto): string | null {
    if (this.failedCoverIds().has(book.readthroughId)) return null;
    return this.urlHelper.getDirectThumbnailUrl(book.bookId);
  }

  onCoverError(book: ReadthroughSummaryDto): void {
    this.failedCoverIds.update(s => { const n = new Set(s); n.add(book.readthroughId); return n; });
  }

  goToHistory(book: ReadthroughSummaryDto): void {
    if (this.metadataCenterViewMode() === 'route') {
      this.router.navigate(['/book', book.bookId], {queryParams: {tab: 'history'}});
    } else {
      this.bookDialogHelper.openBookDetailsDialog(book.bookId, 'history');
    }
  }

  getAuthors(book: ReadthroughSummaryDto): string {
    return book.authors ?? '';
  }

  getBookAuthors(book: Book): string {
    return book.metadata?.authors?.join(', ') ?? '';
  }

  filterLogReadBooks(event: {query: string}): void {
    const q = event.query.toLowerCase();
    this.logReadFilteredOptions = this.bookService.books()
      .filter(b => (b.metadata?.title ?? '').toLowerCase().includes(q))
      .map(b => ({book: b, label: b.metadata?.title ?? `Book #${b.id}`}))
      .slice(0, 20);
  }

  openLogReadDialog(): void {
    this.logReadOption = null;
    this.logReadFilteredOptions = [];
    this.logReadStartedOn = null;
    this.logReadFinishedOn = null;
    this.logReadNotes = '';
    this.showLogReadDialog = true;
  }

  saveLogRead(): void {
    if (!this.logReadOption || !this.logReadFinishedOn) return;
    const request: BookReadthroughRequest = {
      startedOn: this.logReadStartedOn ? this.toIsoDate(this.logReadStartedOn) : null,
      finishedOn: this.toIsoDate(this.logReadFinishedOn),
      notes: this.logReadNotes.trim() || null,
    };
    this.readthroughService.createReadthrough(this.logReadOption.book.id, request)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.showLogReadDialog = false;
          this.loadSummary();
        },
        error: () => {
          this.messageService.add({
            severity: 'error',
            summary: this.t.translate('common.error'),
            detail: this.t.translate('common.genericError'),
          });
        },
      });
  }

  private toIsoDate(date: Date): string {
    const y = date.getFullYear();
    const m = String(date.getMonth() + 1).padStart(2, '0');
    const d = String(date.getDate()).padStart(2, '0');
    return `${y}-${m}-${d}`;
  }
}
