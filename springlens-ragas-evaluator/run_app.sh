#!/bin/bash

echo "Starting setup..."

# Step 1: Create virtual environment
if [ ! -d "venv" ]; then
  echo "📦 Creating virtual environment..."
  python3 -m venv venv
else
  echo "Virtual environment already exists"
fi

# Step 2: Activate virtual environment
echo "Activating virtual environment..."
source venv/bin/activate

# Step 3: Upgrade pip (optional but recommended)
echo "Upgrading pip..."
pip install --upgrade pip

# Step 4: Install dependencies
echo "Installing dependencies from requirements.txt..."
pip install -r requirements.txt

# Step 5: Run application
echo "Starting application..."
python3 main.py