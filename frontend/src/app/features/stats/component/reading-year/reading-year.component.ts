import {
  Component,
  computed,
  DestroyRef,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {DecimalPipe} from '@angular/common';
import {FormsModule} from '@angular/forms';
import {BaseChartDirective} from 'ng2-charts';
import {ChartConfiguration, ChartData} from 'chart.js';
import {ConfirmationService, MessageService} from 'primeng/api';
import {Button} from 'primeng/button';
import {Dialog} from 'primeng/dialog';
import {InputNumber} from 'primeng/inputnumber';
import {ProgressSpinner} from 'primeng/progressspinner';
import {ConfirmDialog} from 'primeng/confirmdialog';
import {Tooltip} from 'primeng/tooltip';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {ReadingGoalService} from './service/reading-goal.service';
import {YearlySummaryResponse} from './model/reading-goal.model';
import {ReadthroughSummaryDto} from './model/readthrough.model';

const MONTH_LABELS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

@Component({
  selector: 'app-reading-year',
  standalone: true,
  imports: [
    DecimalPipe,
    FormsModule,
    BaseChartDirective,
    Button,
    Dialog,
    InputNumber,
    ProgressSpinner,
    ConfirmDialog,
    Tooltip,
    TranslocoDirective,
  ],
  providers: [ConfirmationService],
  templateUrl: './reading-year.component.html',
  styleUrls: ['./reading-year.component.scss'],
})
export class ReadingYearComponent implements OnInit {
  private readonly goalService = inject(ReadingGoalService);
  private readonly confirmationService = inject(ConfirmationService);
  private readonly messageService = inject(MessageService);
  private readonly t = inject(TranslocoService);
  private readonly destroyRef = inject(DestroyRef);

  readonly currentYear = new Date().getFullYear();
  year = signal(this.currentYear);
  loading = signal(false);
  summary = signal<YearlySummaryResponse | null>(null);

  // Goal dialog
  showGoalDialog = false;
  goalInput: number = 0;

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
  readonly totalPages = computed(() => this.summary()?.totalPages ?? 0);
  readonly currentPace = computed(() => this.summary()?.currentPace ?? 0);
  readonly books = computed(() => this.summary()?.books ?? []);
  readonly isCurrentYear = computed(() => this.year() === this.currentYear);

  readonly goalProgress = computed(() => {
    const g = this.goal();
    if (!g) return 0;
    return Math.min(1, this.booksRead() / g.bookGoal);
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

  ngOnInit(): void {
    this.loadSummary();
  }

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
    this.loading.set(true);
    this.goalService.getYearlySummary(this.year())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (data) => {
          this.summary.set(data);
          this.updateChart(data);
          this.loading.set(false);
        },
        error: () => {
          this.summary.set(null);
          this.loading.set(false);
        },
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
    this.goalInput = this.goal()?.bookGoal ?? 0;
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
    return new Date(isoDate).toLocaleDateString(undefined, {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
    });
  }

  getCoverUrl(book: ReadthroughSummaryDto): string | null {
    return book.bookCoverUrl ?? null;
  }

  getAuthors(book: ReadthroughSummaryDto): string {
    return book.authors?.join(', ') ?? '';
  }
}
