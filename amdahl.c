#include <stdio.h>
#include <stdbool.h> //purely to use bool in bubble sort
#include <time.h> // time
#include <stdlib.h>

void swap(int* xp, int* yp){
    int temp = *xp;
    *xp = *yp;
    *yp = temp;
}

void bubbleSort(int arr[], int n){
    int i, j;
    bool swapped;
    for (i = 0; i < n - 1; i++) {
        swapped = false;
        for (j = 0; j < n - i - 1; j++) {
            if (arr[j] > arr[j + 1]) {
                swap(&arr[j], &arr[j + 1]);
                swapped = true;
            }
        }

        // If no two elements were swapped by inner loop,
        // then break
        if (swapped == false)
            break;
    }
}

void printArray(int arr[], int size){
    int i;
    for (i = 0; i < size; i++)
        printf("%d ", arr[i]);
}

void merge(int arr[], int l, int m, int r){
    
    int i, j, k;
    int n1 = m - l + 1;
    int n2 = r - m;

    // Create temp arrays
    int L[n1], R[n2];

    // Copy data to temp arrays L[] and R[]
    for (i = 0; i < n1; i++)
        L[i] = arr[l + i];
    for (j = 0; j < n2; j++)
        R[j] = arr[m + 1 + j];

    // Merge the temp arrays back into arr[l..r
    i = 0;
    j = 0;
    k = l;
    while (i < n1 && j < n2) {
        if (L[i] <= R[j]) {
            arr[k] = L[i];
            i++;
        }
        else {
            arr[k] = R[j];
            j++;
        }
        k++;
    }

    // Copy the remaining elements of L[],
    // if there are any
    while (i < n1) {
        arr[k] = L[i];
        i++;
        k++;
    }

    // Copy the remaining elements of R[],
    // if there are any
    while (j < n2) {
        arr[k] = R[j];
        j++;
        k++;
    }
}

// l is for left index and r is right index of the
// sub-array of arr to be sorted
void mergeSort(int arr[], int l, int r){
    
    if (l < r) {
        int m = l + (r - l) / 2;

        // Sort first and second halves
        mergeSort(arr, l, m);
        mergeSort(arr, m + 1, r);

        merge(arr, l, m, r);
    }
}

int main() { //driver
    
    clock_t T_unoptimized;
    T_unoptimized = clock(); //start timer
    
    int array_size = 5000;
    //int arr[] = {5, 15, 10, 25, 20}; test array
    int randArr[array_size] = {};//for bubble
    
    for (int i = 0; i < array_size; i++) {
        randArr[i] = rand() % (10000);
    } //generate data in memory

    int n = sizeof(randArr) / sizeof(randArr[0]);

    //int n = sizeof(arr) / sizeof(arr[0]); test    
    //bubbleSort(arr, n);
    //mergeSort(arr, 0, n - 1);  tests
    
    clock_t T_processing_unoptimized;
    T_processing_unoptimized = clock(); //algor timing
    
    bubbleSort(randArr, n); //unoptimized

    T_unoptimized = clock() - T_unoptimized; //stop timer
    T_processing_unoptimized = clock() - T_processing_unoptimized; //stop timer for algor op
    
    double time_taken = ((double)T_unoptimized)/CLOCKS_PER_SEC; // in seconds total unop
    printf("%f\n", time_taken); 
    double time_taken2 = ((double)T_processing_unoptimized)/CLOCKS_PER_SEC; // in seconds algor unop
    printf("%f\n", time_taken2); 

//SPLIT TO USE OPTIMIZED 
    clock_t T_optimized;
    T_optimized = clock();//timer start
    
    int array_size2 = 5000;
    int randArr2[array_size2] = {};
    int m = sizeof(randArr2) / sizeof(randArr2[0]);

    for (int i = 0; i < array_size; i++) {
        randArr2[i] = rand() % (10000);
    } //generate data in memory

    clock_t T_processing_optimized;
    T_processing_optimized = clock();//optimized algor
    mergeSort(randArr, 0, m - 1);

    T_optimized = clock() - T_optimized; //stop timer
    T_processing_optimized = clock() - T_processing_optimized; //stop timer for algor op
    
    double time_taken3 = ((double)T_optimized)/CLOCKS_PER_SEC; // in seconds total unop
    printf("%f\n", time_taken3); 
    double time_taken4 = ((double)T_processing_optimized)/CLOCKS_PER_SEC; // in seconds algor unop
    printf("%f\n", time_taken4); 
    printf("order is unop total, unop algor, op total, op algor.\n");

    double overallSpeedup = (T_unoptimized / T_optimized);
    double P = (T_processing_unoptimized / T_unoptimized);
    double S = (T_processing_unoptimized / T_processing_optimized);

    double theoSpeedup = (1 / ((1 - P) + (P / S)));
    printf("%f is measured overall speedup\n", overallSpeedup);
    printf("%f is theoretical overall speedup\n", theoSpeedup);

    return 0;
}
