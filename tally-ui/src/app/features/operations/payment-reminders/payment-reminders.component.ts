import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit } from '@angular/core';
import { FormBuilder, Validators } from '@angular/forms';

import {
  PaymentReminderPayload,
  PaymentReminderRecord,
  PaymentRemindersApiService,
} from '../../../core/api/payment-reminders-api.service';

@Component({
  selector: 'app-payment-reminders',
  templateUrl: './payment-reminders.component.html',
  styleUrls: ['./payment-reminders.component.scss'],
})
export class PaymentRemindersComponent implements OnInit {
  reminders: PaymentReminderRecord[] = [];
  isLoading = true;
  isSubmitting = false;
  activeReminderId: number | null = null;
  errorMessage = '';
  submitMessage = '';

  readonly form = this.formBuilder.group({
    partyName: ['', Validators.required],
    reminderType: ['RECEIVABLE', Validators.required],
    contactName: [''],
    email: [''],
    mobile: [''],
    dueDate: [this.todayPlusDays(7), Validators.required],
    amount: ['1000.00', Validators.required],
    currencyCode: ['INR', Validators.required],
    notes: ['Follow up on outstanding payment.'],
  });

  constructor(
    private formBuilder: FormBuilder,
    private paymentRemindersApiService: PaymentRemindersApiService
  ) {}

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.isLoading = true;
    this.errorMessage = '';
    this.paymentRemindersApiService.getReminders().subscribe({
      next: (response) => {
        this.reminders = (response.reminders || []).slice().sort((left, right) => {
          const dueCompare = (left.dueDate || '').localeCompare(right.dueDate || '');
          if (dueCompare !== 0) {
            return dueCompare;
          }
          return (right.createdAt || '').localeCompare(left.createdAt || '');
        });
        this.isLoading = false;
      },
      error: (error: HttpErrorResponse) => {
        this.errorMessage = this.resolveError(error);
        this.isLoading = false;
      },
    });
  }

  submit(): void {
    if (this.form.invalid || this.isSubmitting) {
      this.form.markAllAsTouched();
      return;
    }

    this.isSubmitting = true;
    this.submitMessage = '';
    this.errorMessage = '';
    const payload = this.form.getRawValue() as PaymentReminderPayload;

    this.paymentRemindersApiService.createReminder(payload).subscribe({
      next: (reminder) => {
        this.isSubmitting = false;
        this.submitMessage = `Reminder created for "${reminder.partyName}".`;
        this.form.reset({
          partyName: '',
          reminderType: 'RECEIVABLE',
          contactName: '',
          email: '',
          mobile: '',
          dueDate: this.todayPlusDays(7),
          amount: '1000.00',
          currencyCode: 'INR',
          notes: 'Follow up on outstanding payment.',
        });
        this.load();
      },
      error: (error: HttpErrorResponse) => {
        this.isSubmitting = false;
        this.errorMessage = this.resolveError(error);
      },
    });
  }

  send(reminder: PaymentReminderRecord): void {
    if (this.activeReminderId !== null) {
      return;
    }
    this.activeReminderId = reminder.id;
    this.submitMessage = '';
    this.errorMessage = '';

    this.paymentRemindersApiService.sendReminder(reminder.id).subscribe({
      next: () => {
        this.activeReminderId = null;
        this.submitMessage = `Reminder sent for "${reminder.partyName}".`;
        this.load();
      },
      error: (error: HttpErrorResponse) => {
        this.activeReminderId = null;
        this.errorMessage = this.resolveError(error);
      },
    });
  }

  resolve(reminder: PaymentReminderRecord): void {
    if (this.activeReminderId !== null) {
      return;
    }
    this.activeReminderId = reminder.id;
    this.submitMessage = '';
    this.errorMessage = '';

    this.paymentRemindersApiService.resolveReminder(reminder.id).subscribe({
      next: () => {
        this.activeReminderId = null;
        this.submitMessage = `Reminder resolved for "${reminder.partyName}".`;
        this.load();
      },
      error: (error: HttpErrorResponse) => {
        this.activeReminderId = null;
        this.errorMessage = this.resolveError(error);
      },
    });
  }

  delete(reminder: PaymentReminderRecord): void {
    if (this.activeReminderId !== null) {
      return;
    }
    this.activeReminderId = reminder.id;
    this.submitMessage = '';
    this.errorMessage = '';

    this.paymentRemindersApiService.deleteReminder(reminder.id).subscribe({
      next: () => {
        this.activeReminderId = null;
        this.submitMessage = `Reminder deleted for "${reminder.partyName}".`;
        this.load();
      },
      error: (error: HttpErrorResponse) => {
        this.activeReminderId = null;
        this.errorMessage = this.resolveError(error);
      },
    });
  }

  get pendingCount(): number {
    return this.reminders.filter((item) => item.status === 'PENDING').length;
  }

  get resolvedCount(): number {
    return this.reminders.filter((item) => item.status === 'RESOLVED').length;
  }

  get sentCount(): number {
    return this.reminders.filter((item) => item.status === 'SENT').length;
  }

  formatAmount(reminder: PaymentReminderRecord): string {
    const amount = Number(reminder.amount || 0);
    const currencyCode = reminder.currencyCode || 'INR';
    return new Intl.NumberFormat('en-IN', {
      style: 'currency',
      currency: currencyCode,
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    }).format(amount);
  }

  private todayPlusDays(days: number): string {
    const date = new Date();
    date.setDate(date.getDate() + days);
    return date.toISOString().slice(0, 10);
  }

  private resolveError(error: HttpErrorResponse): string {
    if (error.error && typeof error.error.detail === 'string') {
      return error.error.detail;
    }
    if (error.error && typeof error.error.message === 'string') {
      return error.error.message;
    }
    return 'Unable to complete the payment reminder request.';
  }
}
