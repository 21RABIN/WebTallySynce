import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export interface PaymentReminderRecord {
  id: number;
  partyName: string;
  reminderType: 'RECEIVABLE' | 'PAYABLE';
  contactName?: string;
  email?: string;
  mobile?: string;
  dueDate: string;
  amount: number;
  currencyCode: string;
  status: 'PENDING' | 'SENT' | 'RESOLVED';
  notes?: string;
  createdBy?: string;
  updatedBy?: string;
  lastSentAt?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface PaymentReminderPayload {
  partyName: string;
  reminderType: 'RECEIVABLE' | 'PAYABLE';
  contactName?: string;
  email?: string;
  mobile?: string;
  dueDate: string;
  amount: string;
  currencyCode: string;
  status?: 'PENDING' | 'SENT' | 'RESOLVED';
  notes?: string;
}

@Injectable({
  providedIn: 'root',
})
export class PaymentRemindersApiService {
  constructor(private http: HttpClient) {}

  getReminders(): Observable<{ reminders: PaymentReminderRecord[] }> {
    return this.http.get<{ reminders: PaymentReminderRecord[] }>('/api/payment-reminders');
  }

  createReminder(payload: PaymentReminderPayload): Observable<PaymentReminderRecord> {
    return this.http.post<PaymentReminderRecord>('/api/payment-reminders', payload);
  }

  sendReminder(id: number): Observable<PaymentReminderRecord> {
    return this.http.post<PaymentReminderRecord>(`/api/payment-reminders/${id}/send`, {});
  }

  resolveReminder(id: number): Observable<PaymentReminderRecord> {
    return this.http.post<PaymentReminderRecord>(`/api/payment-reminders/${id}/resolve`, {});
  }

  deleteReminder(id: number): Observable<{ deleted: boolean; id: number }> {
    return this.http.delete<{ deleted: boolean; id: number }>(`/api/payment-reminders/${id}`);
  }
}
